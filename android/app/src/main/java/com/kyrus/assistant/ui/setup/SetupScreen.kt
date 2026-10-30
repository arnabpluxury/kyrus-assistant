package com.kyrus.assistant.ui.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.SetupManifest
import com.kyrus.assistant.ui.AppViewModel

@Composable
fun SetupScreen(vm: AppViewModel) {
    val progress by vm.setupProgress.collectAsState()
    val anim by animateFloatAsState(targetValue = progress.fraction, label = "setup")
    val free = vm.freeBytes()
    val need = SetupManifest.totalDiskBytes()
    val download = SetupManifest.totalDownloadBytes()
    val running = progress.phase in setOf("prefix", "download")
    val done = progress.finished || progress.phase == "done"

    LaunchedEffect(done) {
        // ViewModel flips setupComplete when bootstrap finishes; this keeps UI honest.
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Kyrus",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "On-device assistant. No Termux. No accounts.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("What will be installed", style = MaterialTheme.typography.titleLarge)
        Text(
            "The APK stays small. After you tap Continue, Kyrus prepares a private runtime " +
                "(like Termux’s prefix, but only inside this app) and downloads the model.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupManifest.components().forEach { c ->
            ComponentCard(
                title = c.title,
                detail = c.detail,
                size = SetupManifest.formatBytes(c.bytes),
                network = c.url != null,
            )
        }

        SummaryRow("Download now", SetupManifest.formatBytes(download))
        SummaryRow("Disk after setup", SetupManifest.formatBytes(need))
        SummaryRow("Free on this phone", if (free > 0) SetupManifest.formatBytes(free) else "—")

        if (progress.phase != "idle") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = progress.detail.ifBlank { progress.phase },
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { anim },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp)),
                )
                if (progress.bytesTotal > 0) {
                    Text(
                        "${SetupManifest.formatBytes(progress.bytesDone)} / ${SetupManifest.formatBytes(progress.bytesTotal)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                progress.error?.let {
                    Text(
                        "Error: ${progress.detail}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { vm.startSetup() },
            enabled = !running && !done,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                when {
                    done -> "Opening chat…"
                    running -> "Working…"
                    else -> "Continue — download & set up"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        TextButton(
            onClick = { /* transparency only */ },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Uses GitHub Releases · Open Contribution license", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ComponentCard(title: String, detail: String, size: String, network: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(size, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (network) "Network download" else "Created on device",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
