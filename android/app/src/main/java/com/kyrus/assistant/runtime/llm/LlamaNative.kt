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
        } catch (_: Throwable) {
            loaded = false
            false
        }
    }

    fun interface TokenCallback {
        /** Return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    external fun nativeInit(): Boolean
    external fun nativeLoad(path: String, nCtx: Int): Boolean
    external fun nativeUnload()
    external fun nativeIsReady(): Boolean
    external fun nativeStop()
    external fun nativeClearHistory()
    external fun nativeComplete(system: String, user: String, maxTokens: Int, temperature: Float): String
    external fun nativeCompleteStream(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        clearKv: Boolean,
        callback: TokenCallback?,
    ): String
}
