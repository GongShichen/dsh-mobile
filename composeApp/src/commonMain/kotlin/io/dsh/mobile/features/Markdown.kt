package io.dsh.mobile.features

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Streaming-safe renderer: incomplete fences remain code, and never execute embedded HTML. */
@Composable
internal fun MarkdownText(text: String) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                val newline = part.indexOf('\n')
                CodeBlock(if (newline >= 0) part.substring(newline + 1) else part, if (newline >= 0) part.substring(0, newline).trim() else "")
            } else if (part.isNotBlank()) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { part.trim().lines().forEach { line ->
                    val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
                    val value = when { heading != null -> line.drop(heading + 1); line.startsWith("- ") || line.startsWith("* ") -> "• ${line.drop(2)}"; line.startsWith("> ") -> "│ ${line.drop(2)}"; else -> line }
                    Text(inlineMarkdown(value), style = if (heading != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                } }
            }
        }
    }
}

private fun inlineMarkdown(value: String): AnnotatedString = buildAnnotatedString {
    val regex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    var offset = 0
    regex.findAll(value).forEach { match ->
        append(value.substring(offset, match.range.first))
        if (match.value.startsWith("**")) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.value.removeSurrounding("**")) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.value.removeSurrounding("`")) }
        offset = match.range.last + 1
    }
    append(value.substring(offset))
}

@Composable
internal fun CodeBlock(value: String, language: String = "") {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language.ifBlank { "文本" }, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }, contentPadding = PaddingValues(0.dp)) { Text("复制") }
            }
            SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState())) }
        }
    }
}
