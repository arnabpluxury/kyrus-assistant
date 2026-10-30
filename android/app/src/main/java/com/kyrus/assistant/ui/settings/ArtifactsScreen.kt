package com.kyrus.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.RuntimePaths
import java.io.File

/**
 * Claude-style artifacts list: recent HTML/MD/code files from the sandbox workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(onBack: () -> Unit) {
    val files = remember {
        runCatching {
            RuntimePaths.workspace.walkTopDown().maxDepth(6)
                .filter { it.isFile && it.extension.lowercase() in setOf("html", "md", "py", "js", "css", "json", "txt") }
                .sortedByDescending { it.lastModified() }
                .take(40)
                .toList()
        }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Artifacts") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Generated files in your sandboxed workspace (Claude Artifacts–style browser).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (files.isEmpty()) {
                item {
                    Text("No artifacts yet — ask Kyrus to scaffold a project or write a file.")
                }
            }
            items(files, key = { it.absolutePath }) { f ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        f.relativeTo(RuntimePaths.workspace).path,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        f.readText().take(160).replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
