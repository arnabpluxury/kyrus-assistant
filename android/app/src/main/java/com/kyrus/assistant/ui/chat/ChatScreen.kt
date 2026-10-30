package com.kyrus.assistant.ui.chat

import android.Manifest
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kyrus.assistant.runtime.agent.ChatMessage
import com.kyrus.assistant.runtime.agent.PermissionRequest
import com.kyrus.assistant.runtime.agent.RunPhase
import com.kyrus.assistant.ui.AppViewModel
import com.kyrus.assistant.ui.components.MarkdownText
import com.kyrus.assistant.ui.components.RunningStatusBar
import com.kyrus.assistant.ui.components.ThinkingRow
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(
    vm: AppViewModel,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenData: () -> Unit,
    onOpenArtifacts: () -> Unit = {},
) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val pending by vm.pendingPermission.collectAsState()
    val llmStatus by vm.llmStatus.collectAsState()
    val runStatus by vm.runStatus.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val conversationId by vm.conversationId.collectAsState()
    val backend by vm.backend.collectAsState()

    var draft by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val act = ctx as? Activity
        if (granted && act != null) {
            vm.voice.listen(
                activity = act,
                onPartial = { draft = it },
                onResult = { draft = it },
                onError = {},
            )
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val copied = uris.mapNotNull { uri ->
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
                val dest = File(ctx.cacheDir, name)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest.absolutePath
            } catch (_: Exception) {
                null
            }
        }
        pendingAttachments = pendingAttachments + copied
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Kyrus",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("New chat") },
                        selected = false,
                        onClick = {
                            vm.newChat()
                            scope.launch { drawerState.close() }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(conversations, key = { it.id }) { c ->
                            NavigationDrawerItem(
                                label = { Text(c.title.ifBlank { "Chat" }, maxLines = 1) },
                                selected = c.id == conversationId,
                                onClick = {
                                    vm.openConversation(c.id)
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(label = { Text("Projects") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onOpenProjects()
                    })
                    NavigationDrawerItem(label = { Text("Artifacts") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onOpenArtifacts()
                    })
                    NavigationDrawerItem(label = { Text("Providers (BYOK)") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onOpenProviders()
                    })
                    NavigationDrawerItem(label = { Text("Import / Export") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onOpenData()
                    })
                    NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    })
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kyrus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$llmStatus · ${backend.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    IconButton(onClick = { vm.stopGeneration() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                }
            }

            if (runStatus.phase != RunPhase.Idle) {
                RunningStatusBar(runStatus)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            if (pendingAttachments.isNotEmpty()) {
                Text(
                    "Attachments: ${pendingAttachments.joinToString { File(it).name }}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { fileLauncher.launch(arrayOf("*/*", "image/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (busy) "Working…" else "Message Kyrus") },
                    enabled = !busy,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                )
                IconButton(
                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice")
                }
                FilledIconButton(
                    onClick = {
                        val atts = pendingAttachments
                        pendingAttachments = emptyList()
                        vm.send(draft, atts)
                        draft = ""
                    },
                    enabled = !busy && (draft.isNotBlank() || pendingAttachments.isNotEmpty()),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    pending?.let { req ->
        PermissionDialog(req) { once, session, deny ->
            vm.resolvePermission(once, session, deny)
        }
    }
}

@Composable
private fun PermissionDialog(
    req: PermissionRequest,
    onResult: (allowOnce: Boolean, allowSession: Boolean, deny: Boolean) -> Unit,
) {
    val title = when (req.kind) {
        PermissionRequest.Kind.Network -> "Allow internet?"
        PermissionRequest.Kind.DlpConfirm -> "Share local data online?"
        PermissionRequest.Kind.ReadStorage, PermissionRequest.Kind.WriteStorage -> "Allow storage access?"
        PermissionRequest.Kind.Install -> "Allow install?"
        PermissionRequest.Kind.Camera -> "Allow camera?"
        PermissionRequest.Kind.Shell -> "Allow shell?"
        PermissionRequest.Kind.Write -> "Allow write?"
    }
    AlertDialog(
        onDismissRequest = { onResult(false, false, true) },
        title = { Text(title) },
        text = { Text(req.detail) },
        confirmButton = {
            TextButton(onClick = { onResult(true, false, false) }) { Text("Allow once") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onResult(false, true, false) }) { Text("Allow session") }
                TextButton(onClick = { onResult(false, false, true) }) { Text("Deny") }
            }
        },
    )
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val mine = msg.role == ChatMessage.Role.User
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (mine) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(msg.text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            msg.attachmentPaths.forEach { path ->
                Text(
                    File(path).name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            // Claude-like: assistant flush left, no bubble
            Column(modifier = Modifier.fillMaxWidth(0.98f)) {
                ThinkingRow(msg.thinking, msg.toolTrace)
                if (msg.text.isNotBlank()) {
                    MarkdownText(msg.text)
                } else if (msg.status != null) {
                    Text(msg.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                msg.imagePaths.forEach { path ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Fetched image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (msg.errorCode != null) {
                    Text(
                        msg.errorCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
