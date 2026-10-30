package com.kyrus.assistant.runtime

/**
 * Structured errors for setup, LLM, tools, network, and BYOK — always include a stable code.
 */
data class KyrusError(
    val code: String,
    val category: Category,
    val message: String,
    val causeMessage: String? = null,
    val retryable: Boolean = false,
) {
    enum class Category {
        Setup, Llm, Tool, Network, Permission, Storage, Provider, Data, Unknown
    }

    fun toUserString(): String = buildString {
        append("[$code] $message")
        if (!causeMessage.isNullOrBlank()) append(" — $causeMessage")
        if (retryable) append(" (retryable)")
    }

    companion object {
        fun setup(code: String, message: String, cause: String? = null, retryable: Boolean = false) =
            KyrusError(code, Category.Setup, message, cause, retryable)

        fun llm(code: String, message: String, cause: String? = null, retryable: Boolean = false) =
            KyrusError(code, Category.Llm, message, cause, retryable)

        fun tool(code: String, message: String, cause: String? = null, retryable: Boolean = false) =
            KyrusError(code, Category.Tool, message, cause, retryable)

        fun network(code: String, message: String, cause: String? = null, retryable: Boolean = true) =
            KyrusError(code, Category.Network, message, cause, retryable)

        fun permission(code: String, message: String) =
            KyrusError(code, Category.Permission, message, retryable = false)

        fun provider(code: String, message: String, cause: String? = null, retryable: Boolean = true) =
            KyrusError(code, Category.Provider, message, cause, retryable)

        fun storage(code: String, message: String, cause: String? = null) =
            KyrusError(code, Category.Storage, message, cause, retryable = false)

        fun data(code: String, message: String, cause: String? = null) =
            KyrusError(code, Category.Data, message, cause, retryable = false)
    }
}

object ErrorCodes {
    const val SETUP_LOW_STORAGE = "SETUP_001"
    const val SETUP_PREFIX_FAIL = "SETUP_002"
    const val SETUP_DOWNLOAD_HTTP = "SETUP_003"
    const val SETUP_DOWNLOAD_IO = "SETUP_004"
    const val SETUP_MODEL_VERIFY = "SETUP_005"
    const val SETUP_LLM_SMOKE = "SETUP_006"
    const val LLM_LIB_MISSING = "LLM_001"
    const val LLM_LOAD_FAIL = "LLM_002"
    const val LLM_GENERATE_FAIL = "LLM_003"
    const val LLM_STOPPED = "LLM_004"
    const val LLM_NOT_READY = "LLM_005"
    const val TOOL_UNKNOWN = "TOOL_001"
    const val TOOL_PATH_ESCAPE = "TOOL_002"
    const val TOOL_DENIED = "TOOL_003"
    const val TOOL_FAIL = "TOOL_004"
    const val NET_DENIED = "NET_001"
    const val NET_HTTP = "NET_002"
    const val NET_DLP_BLOCK = "NET_003"
    const val NET_DLP_CONFIRM = "NET_004"
    const val PERM_DENIED = "PERM_001"
    const val STORAGE_NO_GRANT = "STOR_001"
    const val STORAGE_NOT_FOUND = "STOR_002"
    const val PROVIDER_NO_KEY = "BYOK_001"
    const val PROVIDER_AUTH = "BYOK_002"
    const val PROVIDER_HTTP = "BYOK_003"
    const val PROVIDER_OFFLINE = "BYOK_004"
    const val DATA_EXPORT = "DATA_001"
    const val DATA_IMPORT = "DATA_002"
}
