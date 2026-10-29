package com.kyrus.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kyrus.assistant.ui.chat.ChatScreen
import com.kyrus.assistant.ui.settings.SettingsScreen
import com.kyrus.assistant.ui.setup.SetupScreen

@Composable
fun KyrusNav(setupComplete: Boolean, vm: AppViewModel) {
    if (!setupComplete) {
        SetupScreen(vm = vm)
        return
    }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
    } else {
        ChatScreen(vm = vm, onOpenSettings = { showSettings = true })
    }
}
