package com.kyrus.assistant.runtime.llm

import com.kyrus.assistant.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the fine-tuned GGUF and runs chat completions on-device.
 */
class LlamaRuntime {
    private val mutex = Mutex()
    @Volatile
    var available: Boolean = false
        private set
    @Volatile
    var status: String = "not loaded"
        private set

    suspend fun warmUp(nCtx: Int = 1024): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!LlamaNative.ensureLibrary()) {
                status = "Native lib missing (build with NDK)"
                available = false
                return@withLock false
            }
            val model = RuntimePaths.modelFile
            if (!model.exists() || model.length() < 1_000_000L) {
                status = "Model file missing — finish setup"
                available = false
                return@withLock false
            }
            if (!LlamaNative.nativeInit()) {
                status = "llama init failed"
                available = false
                return@withLock false
            }
            val ok = LlamaNative.nativeLoad(model.absolutePath, nCtx)
            available = ok
            status = if (ok) "GGUF ready (${model.length() / (1024 * 1024)} MB)" else "GGUF load failed"
            ok
        }
    }

    suspend fun complete(system: String, user: String, maxTokens: Int = 256, temperature: Float = 0.25f): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!available || !LlamaNative.nativeIsReady()) return@withLock ""
                LlamaNative.nativeComplete(system, user, maxTokens, temperature).trim()
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
