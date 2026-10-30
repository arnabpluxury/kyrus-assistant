package com.kyrus.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kyrus.assistant.ui.chat.ChatScreen
import com.kyrus.assistant.ui.settings.ArtifactsScreen
import com.kyrus.assistant.ui.settings.DataScreen
import com.kyrus.assistant.ui.settings.ProjectsScreen
import com.kyrus.assistant.ui.settings.ProvidersScreen
import com.kyrus.assistant.ui.settings.SettingsScreen
import com.kyrus.assistant.ui.setup.SetupScreen

private enum class Dest { Chat, Settings, Providers, Projects, Data, Artifacts }

@Composable
fun KyrusNav(setupComplete: Boolean, vm: AppViewModel) {
    if (!setupComplete) {
        SetupScreen(vm = vm)
        return
    }
    var dest by remember { mutableStateOf(Dest.Chat) }
    when (dest) {
        Dest.Chat -> ChatScreen(
            vm = vm,
            onOpenSettings = { dest = Dest.Settings },
            onOpenProviders = { dest = Dest.Providers },
            onOpenProjects = { dest = Dest.Projects },
            onOpenData = { dest = Dest.Data },
            onOpenArtifacts = { dest = Dest.Artifacts },
        )
        Dest.Settings -> SettingsScreen(
            vm = vm,
            onBack = { dest = Dest.Chat },
            onOpenProviders = { dest = Dest.Providers },
            onOpenData = { dest = Dest.Data },
        )
        Dest.Providers -> ProvidersScreen(vm = vm, onBack = { dest = Dest.Chat })
        Dest.Projects -> ProjectsScreen(vm = vm, onBack = { dest = Dest.Chat })
        Dest.Data -> DataScreen(vm = vm, onBack = { dest = Dest.Chat })
        Dest.Artifacts -> ArtifactsScreen(onBack = { dest = Dest.Chat })
    }
}
