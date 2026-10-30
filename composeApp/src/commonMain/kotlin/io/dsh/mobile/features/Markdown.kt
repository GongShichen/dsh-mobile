package io.dsh.mobile.features

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock

/** Native GFM; retain the previous parsed frame while streamed text is parsed off the UI thread. */
@Composable
internal fun MarkdownText(text: String, compact: Boolean = false) {
    val body = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val color = if (compact) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val heading = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 27.sp)
    val parsed = rememberMarkdownState(text, retainState = true)
    SelectionContainer {
        Markdown(parsed, modifier = Modifier.fillMaxWidth(),
            colors = markdownColor(text = color, codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant, dividerColor = MaterialTheme.colorScheme.outlineVariant),
            typography = markdownTypography(h1 = heading.copy(fontSize = if (compact) 16.sp else 22.sp), h2 = heading,
                h3 = heading, h4 = heading, h5 = heading, h6 = heading, text = body, paragraph = body,
                ordered = body, bullet = body, list = body, quote = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                table = body.copy(fontSize = if (compact) 13.sp else 14.sp), code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
                textLink = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium))),
            padding = markdownPadding(block = if (compact) 4.dp else 6.dp, list = 2.dp, listItemTop = 2.dp,
                listItemBottom = 2.dp, listIndent = 12.dp, blockQuote = PaddingValues(horizontal = 12.dp)),
            components = markdownComponents(
                codeFence = { model -> MarkdownCodeFence(model.content, model.node) { code, language, _ -> CodeBlock(code.trimEnd(), language.orEmpty()) } },
                codeBlock = { model -> MarkdownCodeBlock(model.content, model.node) { code, _, _ -> CodeBlock(code.trimEnd()) } },
            ),
            loading = { Text(text, style = body, color = color) },
            error = { Text(text, style = body, color = color) },
        )
    }
}

@Composable
internal fun CodeBlock(value: String, language: String = "") {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "代码" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = if (copied) "已复制代码" else "复制代码" }) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { NavigationIcon(if (copied) "CHECK" else "COPY", 16.dp) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) }
        }
    }
}
