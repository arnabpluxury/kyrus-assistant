package com.kyrus.assistant.runtime.agent

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val thinking: String? = null,
    val toolTrace: String? = null,
    val imagePaths: List<String> = emptyList(),
    val attachmentPaths: List<String> = emptyList(),
    val status: String? = null,
    val pendingPermission: PermissionRequest? = null,
    val errorCode: String? = null,
) {
    enum class Role { User, Assistant, System, Tool }
}

data class PermissionRequest(
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        Network, Shell, Write, ReadStorage, WriteStorage, Install, Camera, DlpConfirm
    }
}

/** Live turn status shown like Kyrus Code RunningStatus. */
enum class RunPhase {
    Idle, LoadingModel, Thinking, UsingTool, Writing, Error
}

data class RunStatus(
    val phase: RunPhase = RunPhase.Idle,
    val label: String = "",
    val toolName: String? = null,
    val startedAt: Long = 0L,
)
