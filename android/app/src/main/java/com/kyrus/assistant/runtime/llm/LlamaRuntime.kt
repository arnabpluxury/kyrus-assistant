package com.kyrus.assistant.runtime.llm

import com.kyrus.assistant.runtime.ErrorCodes
import com.kyrus.assistant.runtime.KyrusError
import com.kyrus.assistant.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the fine-tuned GGUF and runs chat completions on-device (streaming + stop).
 */
class LlamaRuntime {
    private val mutex = Mutex()
    @Volatile
    var available: Boolean = false
        private set
    @Volatile
    var status: String = "not loaded"
        private set
    @Volatile
    var lastError: KyrusError? = null
        private set

    suspend fun warmUp(nCtx: Int = 2048): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!LlamaNative.ensureLibrary()) {
                status = "Native lib missing (build with NDK)"
                lastError = KyrusError.llm(ErrorCodes.LLM_LIB_MISSING, status)
                available = false
                return@withLock false
            }
            val model = RuntimePaths.modelFile
            if (!model.exists() || model.length() < 1_000_000L) {
                status = "Model file missing — finish setup"
                lastError = KyrusError.llm(ErrorCodes.LLM_NOT_READY, status)
                available = false
                return@withLock false
            }
            if (!LlamaNative.nativeInit()) {
                status = "llama init failed"
                lastError = KyrusError.llm(ErrorCodes.LLM_LOAD_FAIL, status)
                available = false
                return@withLock false
            }
            val ok = LlamaNative.nativeLoad(model.absolutePath, nCtx)
            available = ok
            status = if (ok) "GGUF ready (${model.length() / (1024 * 1024)} MB)" else "GGUF load failed"
            lastError = if (ok) null else KyrusError.llm(ErrorCodes.LLM_LOAD_FAIL, status)
            ok
        }
    }

    suspend fun complete(
        system: String,
        user: String,
        maxTokens: Int = 256,
        temperature: Float = 0.25f,
    ): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!available || !LlamaNative.nativeIsReady()) return@withLock ""
            LlamaNative.nativeComplete(system, user, maxTokens, temperature).trim()
        }
    }

    /**
     * Stream tokens via [onToken]. Returns full text. Set [clearKv] false to reuse KV cache.
     */
    suspend fun completeStream(
        system: String,
        user: String,
        maxTokens: Int = 384,
        temperature: Float = 0.25f,
        clearKv: Boolean = true,
        onToken: (String) -> Boolean = { true },
    ): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!available || !LlamaNative.nativeIsReady()) {
                lastError = KyrusError.llm(ErrorCodes.LLM_NOT_READY, "Model not ready")
                return@withLock ""
            }
            try {
                LlamaNative.nativeCompleteStream(
                    system,
                    user,
                    maxTokens,
                    temperature,
                    clearKv,
                    LlamaNative.TokenCallback { piece -> onToken(piece) },
                ).trim()
            } catch (e: Exception) {
                lastError = KyrusError.llm(ErrorCodes.LLM_GENERATE_FAIL, "Generate failed", e.message, true)
                ""
            }
        }
    }

    fun stop() {
        if (LlamaNative.ensureLibrary()) {
            try {
                LlamaNative.nativeStop()
            } catch (_: Throwable) {
            }
        }
    }

    fun clearHistory() {
        if (LlamaNative.ensureLibrary()) {
            try {
                LlamaNative.nativeClearHistory()
            } catch (_: Throwable) {
            }
        }
    }

    fun unload() {
        if (LlamaNative.ensureLibrary()) {
            try {
                LlamaNative.nativeUnload()
            } catch (_: Throwable) {
            }
        }
        available = false
        status = "unloaded"
    }
}
