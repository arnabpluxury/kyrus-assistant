package com.kyrus.assistant.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.RuntimePaths
import com.kyrus.assistant.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit = {},
    onOpenData: () -> Unit = {},
) {
    val voice by vm.voiceReplies.collectAsState()
    val llmStatus by vm.llmStatus.collectAsState()
    val backend by vm.backend.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("General", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak replies")
                    Text("TTS after each answer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = voice, onCheckedChange = { vm.setVoiceReplies(it) })
            }

            HorizontalDivider()
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Text("Backend: ${backend.name}", style = MaterialTheme.typography.bodyMedium)
            Text(llmStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onOpenProviders) { Text("Configure providers (BYOK)") }

            HorizontalDivider()
            Text("Privacy & permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Network tools ask before leaving the device. DLP blocks secret-looking payloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { vm.clearNetworkSession() }) { Text("Clear session allows") }

            HorizontalDivider()
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Text(
                "Chats, memory, and the GGUF live under the app sandbox. Uninstalling Kyrus deletes them. Export first if you need a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenData) { Text("Import / Export") }
            Text(
                "Prefix: ${runCatching { RuntimePaths.root.absolutePath }.getOrDefault("—")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Kyrus Assistant 0.4.0 — on-device agent + optional BYOK cloud.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
