package com.kyrus.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.llm.LlmBackend
import com.kyrus.assistant.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(vm: AppViewModel, onBack: () -> Unit) {
    val backend by vm.backend.collectAsState()
    var model by remember { mutableStateOf(vm.cloudModel()) }
    var base by remember { mutableStateOf(vm.customBaseUrl()) }
    var keyDraft by remember(backend) { mutableStateOf(vm.apiKey(backend.name)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Providers (BYOK)") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Keys stay encrypted on-device. Local GGUF works offline without any key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Active backend", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmBackend.entries.chunked(2).forEach { row ->
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        row.forEach { b ->
                            FilterChip(
                                selected = backend == b,
                                onClick = {
                                    vm.setBackend(b)
                                    keyDraft = vm.apiKey(b.name)
                                },
                                label = { Text(b.name) },
                            )
                        }
                    }
                }
            }
            if (backend != LlmBackend.Local) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = {
                        keyDraft = it
                        vm.setApiKey(backend.name, it)
                    },
                    label = { Text("${backend.name} API key") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        vm.setCloudModel(it)
                    },
                    label = { Text("Model id") },
                    placeholder = { Text("e.g. gpt-4o-mini") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (backend == LlmBackend.CustomOpenAI) {
                OutlinedTextField(
                    value = base,
                    onValueChange = {
                        base = it
                        vm.setCustomBaseUrl(it)
                    },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Text(
                "Errors use codes BYOK_001 (no key), BYOK_002 (auth), BYOK_003 (HTTP), BYOK_004 (offline).",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
