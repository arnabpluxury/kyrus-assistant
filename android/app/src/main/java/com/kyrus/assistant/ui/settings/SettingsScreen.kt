package com.kyrus.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.RuntimePaths
import com.kyrus.assistant.runtime.SetupManifest
import com.kyrus.assistant.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val voice by vm.voiceReplies.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    val model = RuntimePaths.modelFile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }

        Text("On-device model", style = MaterialTheme.typography.titleLarge)
        Text(llm, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "File: ${model.name} (${if (model.exists()) SetupManifest.formatBytes(model.length()) else "missing"})",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Workspace: ${RuntimePaths.workspace.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speak replies", style = MaterialTheme.typography.titleMedium)
                Text("TTS for assistant answers (high-end devices)", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = voice, onCheckedChange = { vm.setVoiceReplies(it) })
        }

        TextButton(onClick = { vm.clearNetworkSession() }) {
            Text("Reset internet session permission")
        }

        Text(
            "Internet & shell-like actions always prompt unless you chose “Allow session”. " +
                "Coding tools stay inside the app sandbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
