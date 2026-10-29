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
import java.util.concurrent.TimeUnit

data class SetupProgress(
    val phase: String = "idle",
    val detail: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val componentId: String? = null,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

/**
 * Plug-and-play installer: creates the private prefix, checks free space,
 * downloads the GGUF with live progress — no Termux / no CLI for the user.
 */
class BootstrapManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _progress = MutableStateFlow(SetupProgress())
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

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

    suspend fun runSetup() = withContext(Dispatchers.IO) {
        try {
            val need = SetupManifest.totalDiskBytes()
            val free = freeBytes()
            if (free in 1 until need) {
                _progress.value = SetupProgress(
                    phase = "blocked",
                    detail = "Need ${SetupManifest.formatBytes(need)} free; only ${SetupManifest.formatBytes(free)} available.",
                    error = "low_storage",
                )
                return@withContext
            }

            _progress.value = SetupProgress(
                phase = "prefix",
                detail = "Creating private runtime (sandbox, notes, memory)…",
                componentId = "prefix",
                bytesTotal = SetupManifest.LOCAL_PREFIX_BYTES,
                bytesDone = SetupManifest.LOCAL_PREFIX_BYTES / 4,
            )
            RuntimePaths.init(context)
            // Marker so we know prefix was prepared
            File(RuntimePaths.root, ".ready").writeText("kyrus-prefix-v1\n")
            _progress.value = _progress.value.copy(
                detail = "Runtime prefix ready at app-private storage.",
                bytesDone = SetupManifest.LOCAL_PREFIX_BYTES,
            )

            val model = RuntimePaths.modelFile
            if (model.exists() && model.length() > 1_000_000L) {
                _progress.value = SetupProgress(
                    phase = "done",
                    detail = "Model already installed.",
                    bytesDone = SetupManifest.MODEL_BYTES,
                    bytesTotal = SetupManifest.MODEL_BYTES,
                    componentId = "model",
                    finished = true,
                )
                Prefs(context).setSetupComplete(true)
                return@withContext
            }

            downloadModel(model)
            Prefs(context).setSetupComplete(true)
            _progress.value = SetupProgress(
                phase = "done",
                detail = "You're set — start chatting.",
                bytesDone = SetupManifest.MODEL_BYTES,
                bytesTotal = SetupManifest.MODEL_BYTES,
                componentId = "model",
                finished = true,
            )
        } catch (e: Exception) {
            _progress.value = SetupProgress(
                phase = "error",
                detail = e.message ?: "Setup failed",
                error = e.javaClass.simpleName,
            )
        }
    }

    private fun downloadModel(dest: File) {
        val url = SetupManifest.modelUrl
        val partial = File(dest.parentFile, dest.name + ".partial")
        _progress.value = SetupProgress(
            phase = "download",
            detail = "Downloading Kyrus model from GitHub Releases…",
            componentId = "model",
            bytesTotal = SetupManifest.MODEL_BYTES,
        )

        val req = Request.Builder().url(url).header("User-Agent", "KyrusAssistant-Android/0.2").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} downloading model")
            val body = resp.body ?: error("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: SetupManifest.MODEL_BYTES
            body.byteStream().use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        _progress.value = SetupProgress(
                            phase = "download",
                            detail = "Downloading model… ${SetupManifest.formatBytes(done)} / ${SetupManifest.formatBytes(total)}",
                            bytesDone = done,
                            bytesTotal = total,
                            componentId = "model",
                        )
                    }
                    out.fd.sync()
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }
}
