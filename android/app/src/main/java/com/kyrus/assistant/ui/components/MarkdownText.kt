package com.kyrus.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Lightweight markdown: **bold**, `code`, fenced code blocks, plain paragraphs.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            val blocks = splitFences(text)
            blocks.forEach { block ->
                if (block.code) {
                    Text(
                        text = block.body,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(12.dp),
                    )
                } else {
                    Text(
                        text = inlineFormat(block.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private data class Block(val body: String, val code: Boolean)

private fun splitFences(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val parts = text.split("```")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            val body = part.lineSequence().dropWhile { it.isBlank() || it.matches(Regex("^[a-zA-Z0-9_+-]+$")) }.joinToString("\n").trim()
            out += Block(body.ifBlank { part.trim() }, true)
        } else if (part.isNotBlank()) {
            out += Block(part.trim(), false)
        }
    }
    if (out.isEmpty()) out += Block(text, false)
    return out
}

private fun inlineFormat(src: String) = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(src.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(src[i]); i++
                }
            }
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x22000000))) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(src[i]); i++
                }
            }
            else -> {
                append(src[i]); i++
            }
        }
    }
}
