package com.kyrus.assistant.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyrus.assistant.runtime.SetupLogLevel
import com.kyrus.assistant.runtime.SetupManifest
import com.kyrus.assistant.ui.AppViewModel

@Composable
fun SetupScreen(vm: AppViewModel) {
    val progress by vm.setupProgress.collectAsState()
    val anim by animateFloatAsState(targetValue = progress.fraction, label = "setup")
    val free = vm.freeBytes()
    val need = SetupManifest.totalDiskBytes()
    val download = SetupManifest.totalDownloadBytes()
    val running = progress.phase in setOf(
        "check", "prefix", "integrity", "deps", "download", "verify", "llm_smoke",
    )
    val done = progress.finished || progress.phase == "done"
    val ctx = LocalContext.current
    val logState = rememberLazyListState()

    LaunchedEffect(progress.logLines.size) {
        if (progress.logLines.isNotEmpty()) {
            logState.animateScrollToItem(progress.logLines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            Spacer(modifier = Modifier.height(4.dp))

            Text("What will be installed", style = MaterialTheme.typography.titleLarge)
            Text(
                "The APK stays small. After Continue, Kyrus prepares a private runtime " +
                    "and downloads the model. Watch the live log for every step.",
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
        }

        if (progress.phase != "idle") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = progress.detail.ifBlank { progress.phase },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress.errorCode != null) {
                    Text(
                        "Error ${progress.errorCode}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LinearProgressIndicator(
                    progress = { anim },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp)),
                )
                if (progress.bytesTotal > 0 && progress.phase == "download") {
                    Text(
                        "${SetupManifest.formatBytes(progress.bytesDone)} / ${SetupManifest.formatBytes(progress.bytesTotal)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Live setup log", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = {
                            val text = progress.logLines.joinToString("\n") { line ->
                                val code = line.code?.let { " [$it]" }.orEmpty()
                                "${line.time} ${line.level.name}$code ${line.message}"
                            }
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("kyrus-setup-log", text))
                        },
                        enabled = progress.logLines.isNotEmpty(),
                    ) { Text("Copy") }
                }

                LazyColumn(
                    state = logState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(progress.logLines, key = { it.time + it.message + it.hashCode() }) { line ->
                        val color = when (line.level) {
                            SetupLogLevel.Ok -> MaterialTheme.colorScheme.primary
                            SetupLogLevel.Warn -> MaterialTheme.colorScheme.tertiary
                            SetupLogLevel.Error -> MaterialTheme.colorScheme.error
                            SetupLogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = buildString {
                                append(line.time)
                                append(" ")
                                if (line.code != null) append("[${line.code}] ")
                                append(line.message)
                            },
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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
                    progress.phase == "error" || progress.phase == "blocked" -> "Retry setup"
                    else -> "Continue — download & set up"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ComponentCard(title: String, detail: String, size: String, network: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(size, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (network) "Network download" else "On device",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
