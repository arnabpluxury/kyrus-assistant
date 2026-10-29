package com.kyrus.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kyrus.assistant.runtime.agent.ChatMessage
import com.kyrus.assistant.ui.AppViewModel

@Composable
fun ChatScreen(vm: AppViewModel) {
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val pending by vm.pendingPermission.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    pending?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.resolvePermission(allowOnce = false, allowSession = false, deny = true) },
            title = { Text("Internet permission") },
            text = {
                Text(req.detail + "\n\nKyrus never goes online silently.")
            },
            confirmButton = {
                TextButton(onClick = { vm.resolvePermission(allowOnce = true, allowSession = false, deny = false) }) {
                    Text("Allow once")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.resolvePermission(allowOnce = false, allowSession = true, deny = false) }) {
                        Text("Allow session")
                    }
                    TextButton(onClick = { vm.resolvePermission(allowOnce = false, allowSession = false, deny = true) }) {
                        Text("Deny")
                    }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Kyrus", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "On-device · sandboxed · permissioned tools",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (busy) {
                item {
                    Text(
                        "Working…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Kyrus…") },
                shape = RoundedCornerShape(18.dp),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
            FilledIconButton(
                onClick = {
                    val t = draft
                    draft = ""
                    vm.send(t)
                },
                enabled = draft.isNotBlank() && !busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val mine = msg.role == ChatMessage.Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
            msg.toolTrace?.let {
                Spacer(Modifier = Modifier.height(6.dp))
                Text(
                    "tool · $it",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
