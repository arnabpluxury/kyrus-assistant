package com.kyrus.assistant.ui

import androidx.compose.runtime.Composable
import com.kyrus.assistant.ui.chat.ChatScreen
import com.kyrus.assistant.ui.setup.SetupScreen

@Composable
fun KyrusNav(setupComplete: Boolean, vm: AppViewModel) {
    if (!setupComplete) {
        SetupScreen(vm = vm)
    } else {
        ChatScreen(vm = vm)
    }
}
