package com.kyrus.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kyrus.assistant.runtime.BootstrapManager
import com.kyrus.assistant.runtime.Prefs
import com.kyrus.assistant.runtime.SetupProgress
import com.kyrus.assistant.runtime.agent.ChatMessage
import com.kyrus.assistant.runtime.agent.KyrusEngine
import com.kyrus.assistant.runtime.agent.PermissionRequest
import com.kyrus.assistant.runtime.agent.ToolRegistry
import com.kyrus.assistant.runtime.llm.LlamaRuntime
import com.kyrus.assistant.runtime.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val bootstrap = BootstrapManager(app)
    val voice = VoiceController(app)
    val llm = LlamaRuntime()

    private val _setupComplete = MutableStateFlow(prefs.isSetupComplete() && bootstrap.isReady())
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    val setupProgress: StateFlow<SetupProgress> = bootstrap.progress

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                role = ChatMessage.Role.Assistant,
                text = "I'm Kyrus — on-device, sandboxed, and ready. After setup I'll load your fine-tuned GGUF. Ask me anything; I'll use tools when needed and ask before going online.",
            ),
        ),
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission.asStateFlow()

    private val _llmStatus = MutableStateFlow("warming…")
    val llmStatus: StateFlow<String> = _llmStatus.asStateFlow()

    private val _voiceReplies = MutableStateFlow(prefs.voiceReplies())
    val voiceReplies: StateFlow<Boolean> = _voiceReplies.asStateFlow()

    private var permissionCont: ((Boolean) -> Unit)? = null

    private val engine = KyrusEngine(
        tools = ToolRegistry(
            askNetwork = { detail ->
                prefs.networkSessionAllow()?.let { return@ToolRegistry it }
                suspendCancellableCoroutine { cont ->
                    permissionCont = { allow -> cont.resume(allow) }
                    _pendingPermission.value = PermissionRequest(
                        kind = PermissionRequest.Kind.Network,
                        detail = detail,
                    )
                }
            },
        ),
        llm = llm,
    )

    init {
        voice.initTts()
        if (_setupComplete.value) {
            warmLlm()
        }
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
            _llmStatus.value = "Loading GGUF…"
            val ok = llm.warmUp(nCtx = 1024)
            _llmStatus.value = llm.status
            if (ok) {
                _messages.value = _messages.value + ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = "On-device model loaded. Free-form chat + tools are live.",
                )
            } else {
                _messages.value = _messages.value + ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = "Model file is present but native GGUF engine isn't available yet (${llm.status}). Tool router still works.",
                )
            }
        }
    }

    fun resolvePermission(allowOnce: Boolean, allowSession: Boolean, deny: Boolean) {
        val cont = permissionCont
        permissionCont = null
        _pendingPermission.value = null
        when {
            deny -> cont?.invoke(false)
            allowSession -> {
                prefs.setNetworkSessionAllow(true)
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
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _messages.value = _messages.value + ChatMessage(role = ChatMessage.Role.User, text = trimmed)
            try {
                val result = engine.ask(trimmed)
                _messages.value = _messages.value + ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = result.reply,
                    toolTrace = buildString {
                        if (result.usedLlm) append("gguf")
                        if (result.toolTrace != null) {
                            if (isNotEmpty()) append(" · ")
                            append(result.toolTrace)
                        }
                    }.ifBlank { null },
                )
                if (_voiceReplies.value) {
                    voice.speak(result.reply)
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = "Something went wrong: ${e.message}",
                )
            } finally {
                _busy.value = false
            }
        }
    }

    override fun onCleared() {
        voice.destroy()
        llm.unload()
        super.onCleared()
    }
}
