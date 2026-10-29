package com.kyrus.assistant.runtime.llm

/**
 * JNI surface for on-device GGUF inference (libkyrus_llama.so).
 */
object LlamaNative {
    @Volatile
    private var loaded = false

    fun ensureLibrary(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("kyrus_llama")
            loaded = true
            true
        } catch (t: Throwable) {
            loaded = false
            false
        }
    }

    external fun nativeInit(): Boolean
    external fun nativeLoad(path: String, nCtx: Int): Boolean
    external fun nativeUnload()
    external fun nativeIsReady(): Boolean
    external fun nativeComplete(system: String, user: String, maxTokens: Int, temperature: Float): String
}
