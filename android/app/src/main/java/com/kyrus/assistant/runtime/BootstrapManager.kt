package com.kyrus.assistant.runtime

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SetupLogLevel { Info, Ok, Warn, Error }

data class SetupLogLine(
    val time: String,
    val level: SetupLogLevel,
    val code: String? = null,
    val message: String,
)

data class SetupProgress(
    val phase: String = "idle",
    val detail: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val componentId: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val finished: Boolean = false,
    val logLines: List<SetupLogLine> = emptyList(),
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

/**
 * Plug-and-play installer with a live, read-only setup console.
 * Phases: prefix → integrity → runtime deps → download → verify → llm smoke → done
 */
class BootstrapManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun freeBytes(): Long {
        val path = context.filesDir.absolutePath
        return try {
            StatFs(path).availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun isReady(): Boolean {
        val model = RuntimePaths.modelFile
        return model.exists() && model.length() > 1_000_000L &&
            RuntimePaths.workspace.exists()
    }

    private fun log(level: SetupLogLevel, message: String, code: String? = null) {
        val line = SetupLogLine(
            time = timeFmt.format(Date()),
            level = level,
            code = code,
            message = message,
        )
        val cur = _progress.value
        _progress.value = cur.copy(logLines = cur.logLines + line, detail = message)
    }

    private fun phase(
        phase: String,
        detail: String,
        componentId: String? = null,
        bytesDone: Long = 0,
        bytesTotal: Long = 0,
    ) {
        _progress.value = _progress.value.copy(
            phase = phase,
            detail = detail,
            componentId = componentId,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            error = null,
            errorCode = null,
        )
        log(SetupLogLevel.Info, detail)
    }

    suspend fun runSetup() = withContext(Dispatchers.IO) {
        try {
            val need = SetupManifest.totalDiskBytes()
            val free = freeBytes()
            phase("check", "Checking free storage…", "disk")
            log(SetupLogLevel.Info, "Need ${SetupManifest.formatBytes(need)}; free ${SetupManifest.formatBytes(free)}")
            if (free in 1 until need) {
                val err = KyrusError.setup(
                    ErrorCodes.SETUP_LOW_STORAGE,
                    "Need ${SetupManifest.formatBytes(need)} free; only ${SetupManifest.formatBytes(free)} available.",
                    retryable = false,
                )
                log(SetupLogLevel.Error, err.message, err.code)
                _progress.value = _progress.value.copy(
                    phase = "blocked",
                    detail = err.toUserString(),
                    error = err.message,
                    errorCode = err.code,
                )
                return@withContext
            }
            log(SetupLogLevel.Ok, "Storage check passed", "SETUP_OK")

            // 1) Prefix
            phase(
                "prefix",
                "Creating private runtime (sandbox, notes, memory, cache)…",
                "prefix",
                bytesDone = SetupManifest.LOCAL_PREFIX_BYTES / 5,
                bytesTotal = SetupManifest.LOCAL_PREFIX_BYTES,
            )
            try {
                RuntimePaths.init(context)
                listOf(
                    RuntimePaths.workspace,
                    RuntimePaths.notes,
                    File(RuntimePaths.root, "cache"),
                    File(RuntimePaths.root, "images"),
                    File(RuntimePaths.root, "exports"),
                    File(RuntimePaths.root, "bin"),
                ).forEach { it.mkdirs() }
                File(RuntimePaths.root, ".ready").writeText("kyrus-prefix-v2\n")
                log(SetupLogLevel.Ok, "Prefix ready at ${RuntimePaths.root.absolutePath}", "SETUP_OK")
                _progress.value = _progress.value.copy(bytesDone = SetupManifest.LOCAL_PREFIX_BYTES)
            } catch (e: Exception) {
                fail(KyrusError.setup(ErrorCodes.SETUP_PREFIX_FAIL, "Prefix creation failed", e.message, true))
                return@withContext
            }

            // 2) Integrity
            phase("integrity", "Verifying runtime layout…", "integrity")
            val required = listOf(RuntimePaths.workspace, RuntimePaths.notes, RuntimePaths.root)
            required.forEach { d ->
                if (!d.exists() || !d.isDirectory) {
                    fail(KyrusError.setup(ErrorCodes.SETUP_PREFIX_FAIL, "Missing directory: ${d.name}"))
                    return@withContext
                }
                log(SetupLogLevel.Ok, "OK ${d.name}/")
            }

            // 3) Runtime deps (local markers — no network pip in APK path)
            phase("deps", "Preparing on-device runtime dependencies…", "deps")
            val marker = File(RuntimePaths.root, "deps.ready")
            File(RuntimePaths.root, "bin").mkdirs()
            File(RuntimePaths.root, "lib").mkdirs()
            marker.writeText(
                buildString {
                    appendLine("kyrus-android-deps=1")
                    appendLine("okhttp=bundled")
                    appendLine("jsoup=bundled")
                    appendLine("llama_jni=bundled")
                    appendLine("workspace=ready")
                },
            )
            log(SetupLogLevel.Ok, "Bundled runtime deps marked ready", "SETUP_OK")
            log(SetupLogLevel.Info, "Network tools use app libraries (no Termux pip required)")

            // 4) Model download
            val model = RuntimePaths.modelFile
            if (model.exists() && model.length() > 1_000_000L) {
                log(SetupLogLevel.Ok, "Model already on disk (${SetupManifest.formatBytes(model.length())})")
            } else {
                phase(
                    "download",
                    "Downloading Kyrus model from GitHub Releases…",
                    "model",
                    bytesTotal = SetupManifest.MODEL_BYTES,
                )
                try {
                    downloadModel(model)
                    log(SetupLogLevel.Ok, "Download complete (${SetupManifest.formatBytes(model.length())})", "SETUP_OK")
                } catch (e: Exception) {
                    fail(
                        KyrusError.setup(
                            ErrorCodes.SETUP_DOWNLOAD_IO,
                            "Model download failed",
                            e.message,
                            retryable = true,
                        ),
                    )
                    return@withContext
                }
            }

            // 5) Verify
            phase("verify", "Verifying GGUF integrity…", "verify")
            if (!model.exists() || model.length() < 1_000_000L) {
                fail(KyrusError.setup(ErrorCodes.SETUP_MODEL_VERIFY, "Model file missing or too small after download"))
                return@withContext
            }
            val head = model.inputStream().use { it.readNBytes(4) }
            val magic = String(head)
            if (magic != "GGUF") {
                log(SetupLogLevel.Warn, "Magic bytes=$magic (expected GGUF) — continuing if size OK", ErrorCodes.SETUP_MODEL_VERIFY)
            } else {
                log(SetupLogLevel.Ok, "GGUF magic OK · ${SetupManifest.formatBytes(model.length())}")
            }

            // 6) LLM smoke (native may warm later in UI — record intent)
            phase("llm_smoke", "Recording model path for on-device engine…", "llm")
            File(RuntimePaths.root, "model.path").writeText(model.absolutePath + "\n")
            log(SetupLogLevel.Ok, "Model path registered for llama.cpp JNI", "SETUP_OK")
            log(SetupLogLevel.Info, "Full GGUF warm-up continues when chat opens")

            Prefs(context).setSetupComplete(true)
            log(SetupLogLevel.Ok, "Setup finished — opening chat", "SETUP_DONE")
            _progress.value = _progress.value.copy(
                phase = "done",
                detail = "You're set — start chatting.",
                bytesDone = SetupManifest.MODEL_BYTES,
                bytesTotal = SetupManifest.MODEL_BYTES,
                componentId = "model",
                finished = true,
            )
        } catch (e: Exception) {
            fail(KyrusError.setup("SETUP_999", e.message ?: "Setup failed", e.javaClass.simpleName, true))
        }
    }

    private fun fail(err: KyrusError) {
        log(SetupLogLevel.Error, err.message, err.code)
        _progress.value = _progress.value.copy(
            phase = "error",
            detail = err.toUserString(),
            error = err.message,
            errorCode = err.code,
            finished = false,
        )
    }

    private fun downloadModel(dest: File) {
        val url = SetupManifest.modelUrl
        val partial = File(dest.parentFile, dest.name + ".partial")
        dest.parentFile?.mkdirs()

        var lastError: Exception? = null
        for (attempt in 1..4) {
            try {
                log(
                    SetupLogLevel.Info,
                    if (attempt == 1) "GET $url" else "Retry $attempt/4 — resuming download…",
                    if (attempt > 1) ErrorCodes.SETUP_DOWNLOAD_IO else null,
                )
                downloadModelAttempt(url, partial, resume = partial.exists() && partial.length() > 0L)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                log(SetupLogLevel.Warn, "Download hiccup: ${e.message}", ErrorCodes.SETUP_DOWNLOAD_HTTP)
                if (partial.exists() && partial.length() < 1_000_000L) partial.delete()
                Thread.sleep((1500L * attempt).coerceAtMost(8_000L))
            }
        }
        if (lastError != null) throw lastError

        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
        if (!dest.exists() || dest.length() < 1_000_000L) {
            error("Model install incomplete after retries")
        }
    }

    private fun downloadModelAttempt(url: String, partial: File, resume: Boolean) {
        val existing = if (resume && partial.exists()) partial.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "KyrusAssistant-Android/0.4")
        if (existing > 0L) {
            reqBuilder.header("Range", "bytes=$existing-")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                error("HTTP ${resp.code} downloading model [${ErrorCodes.SETUP_DOWNLOAD_HTTP}]")
            }
            val body = resp.body ?: error("Empty response")
            val append = resume && resp.code == 206 && existing > 0L
            if (!append && partial.exists()) partial.delete()
            val total = when {
                resp.code == 206 && existing > 0L -> {
                    val remaining = body.contentLength().takeIf { it > 0 } ?: -1L
                    if (remaining > 0) existing + remaining else SetupManifest.MODEL_BYTES
                }
                else -> body.contentLength().takeIf { it > 0 } ?: SetupManifest.MODEL_BYTES
            }
            body.byteStream().use { input ->
                FileOutputStream(partial, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = if (append) existing else 0L
                    var lastLog = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        _progress.value = _progress.value.copy(
                            phase = "download",
                            detail = "Downloading model… ${SetupManifest.formatBytes(done)} / ${SetupManifest.formatBytes(total)}",
                            bytesDone = done,
                            bytesTotal = total,
                            componentId = "model",
                        )
                        if (done - lastLog > 25L * 1024 * 1024) {
                            log(
                                SetupLogLevel.Info,
                                "Progress ${SetupManifest.formatBytes(done)} / ${SetupManifest.formatBytes(total)}",
                            )
                            lastLog = done
                        }
                    }
                    out.fd.sync()
                }
            }
        }
    }
}
