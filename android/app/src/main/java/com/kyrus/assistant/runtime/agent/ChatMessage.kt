package com.kyrus.assistant.runtime.agent

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val toolTrace: String? = null,
    val pendingPermission: PermissionRequest? = null,
) {
    enum class Role { User, Assistant, System, Tool }
}

data class PermissionRequest(
    val kind: Kind,
    val detail: String,
) {
    enum class Kind { Network, Shell, Write }
}
