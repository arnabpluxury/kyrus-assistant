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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val bootstrap = BootstrapManager(app)

    private val _setupComplete = MutableStateFlow(prefs.isSetupComplete() && bootstrap.isReady())
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    val setupProgress: StateFlow<SetupProgress> = bootstrap.progress

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                role = ChatMessage.Role.Assistant,
                text = "I'm Kyrus — on-device, sandboxed, and ready. Ask me anything; I'll use tools when needed and ask before going online.",
            ),
        ),
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PermissionRequest?>(null)
    val pendingPermission: StateFlow<PermissionRequest?> = _pendingPermission.asStateFlow()

    private var permissionCont: ((Boolean) -> Unit)? = null

    private val engine = KyrusEngine(
        ToolRegistry(
            askNetwork = { detail ->
                // Session remember
                prefs.networkSessionAllow()?.let { return@ToolRegistry it }
                suspendCancellableCoroutine { cont ->
                    permissionCont = { allow ->
                        cont.resume(allow)
                    }
                    _pendingPermission.value = PermissionRequest(
                        kind = PermissionRequest.Kind.Network,
                        detail = detail,
                    )
                }
            },
        ),
    )

    fun freeBytes(): Long = bootstrap.freeBytes()

    fun startSetup() {
        viewModelScope.launch {
            bootstrap.runSetup()
            if (bootstrap.isReady()) {
                _setupComplete.value = true
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
                    toolTrace = result.toolTrace,
                )
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
}
