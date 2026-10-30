package com.kyrus.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kyrus.assistant.runtime.BootstrapManager
import com.kyrus.assistant.runtime.Prefs
import com.kyrus.assistant.runtime.RuntimePaths
import com.kyrus.assistant.runtime.SetupProgress
import com.kyrus.assistant.runtime.agent.ChatMessage
import com.kyrus.assistant.runtime.agent.KyrusEngine
import com.kyrus.assistant.runtime.agent.PermissionRequest
import com.kyrus.assistant.runtime.agent.RunPhase
import com.kyrus.assistant.runtime.agent.RunStatus
import com.kyrus.assistant.runtime.agent.ToolRegistry
import com.kyrus.assistant.runtime.data.ChatRepository
import com.kyrus.assistant.runtime.data.ConversationEntity
import com.kyrus.assistant.runtime.data.MessageEntity
import com.kyrus.assistant.runtime.data.ProjectEntity
import com.kyrus.assistant.runtime.llm.LlmBackend
import com.kyrus.assistant.runtime.llm.LlmClient
import com.kyrus.assistant.runtime.llm.LlamaRuntime
import com.kyrus.assistant.runtime.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val bootstrap = BootstrapManager(app)
    private val repo = ChatRepository(app)
    private val gson = Gson()
    val voice = VoiceController(app)
    val llmRuntime = LlamaRuntime()
    private val llmClient = LlmClient(llmRuntime, prefs)

    private val _setupComplete = MutableStateFlow(prefs.isSetupComplete() && bootstrap.isReady())
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    val setupProgress: StateFlow<SetupProgress> = bootstrap.progress

    private val _conversationId = MutableStateFlow(prefs.activeConversationId())
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()

    val conversations = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projects = repo.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _runStatus = MutableStateFlow(RunStatus())
    val runStatus: StateFlow<RunStatus> = _runStatus.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission.asStateFlow()

    private val _llmStatus = MutableStateFlow("warming…")
    val llmStatus: StateFlow<String> = _llmStatus.asStateFlow()

    private val _voiceReplies = MutableStateFlow(prefs.voiceReplies())
    val voiceReplies: StateFlow<Boolean> = _voiceReplies.asStateFlow()

    private val _backend = MutableStateFlow(prefs.llmBackend())
    val backend: StateFlow<LlmBackend> = _backend.asStateFlow()

    private val _dataStatus = MutableStateFlow<String?>(null)
    val dataStatus: StateFlow<String?> = _dataStatus.asStateFlow()

    private var permissionCont: ((Boolean) -> Unit)? = null

    private val engine = KyrusEngine(
        tools = ToolRegistry(
            context = app,
            askPermission = { req ->
                val sessKey = req.kind.name
                prefs.sessionAllow(sessKey)?.let { return@ToolRegistry it }
                if (req.kind == PermissionRequest.Kind.Network) {
                    prefs.networkSessionAllow()?.let { return@ToolRegistry it }
                }
                suspendCancellableCoroutine { cont ->
                    permissionCont = { allow -> cont.resume(allow) }
                    _pendingPermission.value = req
                }
            },
            dlpConfirm = { detail ->
                suspendCancellableCoroutine { cont ->
                    permissionCont = { allow -> cont.resume(allow) }
                    _pendingPermission.value = PermissionRequest(
                        kind = PermissionRequest.Kind.DlpConfirm,
                        detail = detail,
                    )
                }
            },
        ),
        llm = llmClient,
    )

    init {
        RuntimePaths.ensureInit(app)
        voice.initTts()
        viewModelScope.launch {
            val stored = prefs.activeConversationId()
            val finalId = if (stored != null && repo.getConversation(stored) != null) {
                stored
            } else {
                repo.ensureDefaultConversation().id
            }
            prefs.setActiveConversationId(finalId)
            _conversationId.value = finalId
            reloadMessages(finalId)
        }
        if (_setupComplete.value) {
            warmLlm()
        }
    }

    private suspend fun reloadMessages(cid: String) {
        _messages.value = repo.listMessages(cid).map { repo.toUiMessage(it) }
    }

    fun freeBytes(): Long = bootstrap.freeBytes()

    fun startSetup() {
        viewModelScope.launch {
            bootstrap.runSetup()
            if (bootstrap.isReady()) {
                _setupComplete.value = true
                warmLlm()
            }
        }
    }

    private fun warmLlm() {
        viewModelScope.launch {
            _runStatus.value = RunStatus(RunPhase.LoadingModel, "Loading model…", startedAt = System.currentTimeMillis())
            _llmStatus.value = "Loading GGUF…"
            val ok = llmRuntime.warmUp(nCtx = 2048)
            _llmStatus.value = llmRuntime.status
            _runStatus.value = RunStatus(RunPhase.Idle, if (ok) "Ready" else llmRuntime.status)
            val cid = _conversationId.value ?: return@launch
            val text = if (ok) {
                "On-device model loaded. Free-form chat + tools are live."
            } else {
                "Model file is present but native GGUF engine isn't available yet (${llmRuntime.status}). Tool router / BYOK still work."
            }
            appendAssistant(cid, text)
        }
    }

    private suspend fun appendAssistant(cid: String, text: String, thinking: String? = null, toolTrace: String? = null, images: List<String> = emptyList(), status: String? = null, errorCode: String? = null) {
        val m = ChatMessage(
            role = ChatMessage.Role.Assistant,
            text = text,
            thinking = thinking,
            toolTrace = toolTrace,
            imagePaths = images,
            status = status,
            errorCode = errorCode,
        )
        _messages.value = _messages.value + m
        persist(cid, m)
    }

    private suspend fun persist(cid: String, m: ChatMessage) {
        repo.saveMessage(
            MessageEntity(
                id = m.id,
                conversationId = cid,
                role = m.role.name,
                text = m.text,
                thinking = m.thinking,
                toolTrace = m.toolTrace,
                imagePathsJson = if (m.imagePaths.isEmpty()) null else gson.toJson(m.imagePaths),
                attachmentPathsJson = if (m.attachmentPaths.isEmpty()) null else gson.toJson(m.attachmentPaths),
                status = m.status,
            ),
        )
        if (m.role == ChatMessage.Role.User) {
            repo.touchTitle(cid, m.text)
        }
    }

    fun resolvePermission(allowOnce: Boolean, allowSession: Boolean, deny: Boolean) {
        val cont = permissionCont
        val req = _pendingPermission.value
        permissionCont = null
        _pendingPermission.value = null
        when {
            deny -> cont?.invoke(false)
            allowSession -> {
                req?.let {
                    prefs.setSessionAllow(it.kind.name, true)
                    if (it.kind == PermissionRequest.Kind.Network) prefs.setNetworkSessionAllow(true)
                }
                cont?.invoke(true)
            }
            allowOnce -> cont?.invoke(true)
            else -> cont?.invoke(false)
        }
    }

    fun setVoiceReplies(v: Boolean) {
        prefs.setVoiceReplies(v)
        _voiceReplies.value = v
    }

    fun clearNetworkSession() {
        prefs.setNetworkSessionAllow(null)
        PermissionRequest.Kind.entries.forEach { prefs.setSessionAllow(it.name, null) }
    }

    fun addMountedTree(uri: String) {
        val cur = prefs.mountedTrees().toMutableList()
        if (uri !in cur) cur.add(uri)
        prefs.setMountedTrees(cur)
    }

    fun mountedTrees(): List<String> = prefs.mountedTrees()

    fun setBackend(b: LlmBackend) {
        prefs.setLlmBackend(b)
        _backend.value = b
        _llmStatus.value = when (b) {
            LlmBackend.Local -> llmRuntime.status
            else -> "Cloud: ${b.name}"
        }
    }

    fun setApiKey(provider: String, key: String) {
        prefs.setApiKey(provider, key)
    }

    fun apiKey(provider: String): String = prefs.apiKey(provider).orEmpty()

    fun setCloudModel(m: String) = prefs.setCloudModel(m)
    fun cloudModel(): String = prefs.cloudModel()
    fun setCustomBaseUrl(u: String) = prefs.setCustomBaseUrl(u)
    fun customBaseUrl(): String = prefs.customBaseUrl()

    fun newChat() {
        viewModelScope.launch {
            val c = repo.newConversation()
            prefs.setActiveConversationId(c.id)
            _conversationId.value = c.id
            _messages.value = emptyList()
            llmRuntime.clearHistory()
        }
    }

    fun openConversation(id: String) {
        viewModelScope.launch {
            prefs.setActiveConversationId(id)
            _conversationId.value = id
            reloadMessages(id)
            llmRuntime.clearHistory()
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repo.deleteConversation(id)
            if (_conversationId.value == id) {
                val next = repo.ensureDefaultConversation()
                openConversation(next.id)
            }
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            repo.upsertProject(
                ProjectEntity(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { "Project" },
                ),
            )
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { repo.deleteProject(id) }
    }

    fun exportData(includeModel: Boolean = false) {
        viewModelScope.launch {
            _dataStatus.value = "Exporting…"
            val r = repo.exportData(includeModel)
            _dataStatus.value = r.fold(
                onSuccess = { "Exported: ${it.absolutePath}" },
                onFailure = { it.message },
            )
        }
    }

    fun importData(path: String) {
        viewModelScope.launch {
            _dataStatus.value = "Importing…"
            val r = repo.importData(File(path))
            _dataStatus.value = r.fold(
                onSuccess = {
                    _conversationId.value?.let { reloadMessages(it) }
                    "Import complete"
                },
                onFailure = { it.message },
            )
        }
    }

    fun stopGeneration() {
        llmRuntime.stop()
        _busy.value = false
        _runStatus.value = RunStatus(RunPhase.Idle, "Stopped")
    }

    fun send(text: String, attachmentPaths: List<String> = emptyList()) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachmentPaths.isEmpty()) || _busy.value) return
        val cid = _conversationId.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val started = System.currentTimeMillis()
            _runStatus.value = RunStatus(RunPhase.Thinking, "Thinking…", startedAt = started)
            val userMsg = ChatMessage(
                role = ChatMessage.Role.User,
                text = if (trimmed.isEmpty()) "(attachment)" else trimmed,
                attachmentPaths = attachmentPaths,
            )
            _messages.value = _messages.value + userMsg
            persist(cid, userMsg)

            val assistantId = UUID.randomUUID().toString()
            var streaming = ChatMessage(
                id = assistantId,
                role = ChatMessage.Role.Assistant,
                text = "",
                status = "thinking",
            )
            _messages.value = _messages.value + streaming

            try {
                val hist = _messages.value
                    .filter { it.id != assistantId && it.id != userMsg.id }
                    .takeLast(10)
                    .map {
                        KyrusEngine.HistoryTurn(
                            role = if (it.role == ChatMessage.Role.User) "User" else "Assistant",
                            text = it.text,
                        )
                    }
                val prompt = buildString {
                    append(trimmed)
                    if (attachmentPaths.isNotEmpty()) {
                        append("\n\n[User attached files: ${attachmentPaths.joinToString()}]")
                        append("\nIf an image path is local, describe what you can from the filename/context; cloud vision uses BYOK when configured.")
                    }
                }
                val result = engine.ask(
                    userText = prompt,
                    history = hist,
                    onPhase = { phase, label, tool ->
                        _runStatus.value = RunStatus(phase, label, tool, started)
                        streaming = streaming.copy(status = label)
                        _messages.value = _messages.value.map { if (it.id == assistantId) streaming else it }
                    },
                    onToken = { piece ->
                        streaming = streaming.copy(text = streaming.text + piece, status = "writing")
                        _messages.value = _messages.value.map { if (it.id == assistantId) streaming else it }
                    },
                )
                streaming = streaming.copy(
                    text = result.reply.ifBlank { streaming.text },
                    thinking = result.thinking,
                    toolTrace = buildString {
                        if (result.usedLlm) append(if (_backend.value == LlmBackend.Local) "gguf" else _backend.value.name)
                        if (result.toolTrace != null) {
                            if (isNotEmpty()) append(" · ")
                            append(result.toolTrace)
                        }
                    }.ifBlank { null },
                    imagePaths = result.imagePaths,
                    status = null,
                    errorCode = result.errorCode,
                )
                _messages.value = _messages.value.map { if (it.id == assistantId) streaming else it }
                persist(cid, streaming)
                if (_voiceReplies.value) voice.speak(streaming.text)
            } catch (e: Exception) {
                streaming = streaming.copy(text = "Something went wrong: ${e.message}", status = "error")
                _messages.value = _messages.value.map { if (it.id == assistantId) streaming else it }
                persist(cid, streaming)
            } finally {
                _busy.value = false
                _runStatus.value = RunStatus(RunPhase.Idle, "Ready")
            }
        }
    }

    fun regenerateLast() {
        val lastUser = _messages.value.lastOrNull { it.role == ChatMessage.Role.User } ?: return
        viewModelScope.launch {
            val cid = _conversationId.value ?: return@launch
            // Drop trailing assistant
            val trimmed = _messages.value.toMutableList()
            while (trimmed.isNotEmpty() && trimmed.last().role == ChatMessage.Role.Assistant) {
                trimmed.removeAt(trimmed.lastIndex)
            }
            _messages.value = trimmed
            send(lastUser.text, lastUser.attachmentPaths)
        }
    }

    override fun onCleared() {
        voice.destroy()
        llmRuntime.unload()
        super.onCleared()
    }
}
