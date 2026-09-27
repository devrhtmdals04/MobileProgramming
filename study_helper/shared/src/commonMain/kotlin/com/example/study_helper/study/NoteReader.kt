package com.example.study_helper.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/** Native GFM reading surface. It does not execute HTML or fetch remote images. */
@Composable
internal fun NoteReader(title: String, markdown: String, onOpenLink: (String) -> Unit) {
    var fontSize by rememberSaveable { mutableStateOf(16) }
    val scroll = rememberScrollState()
    val body = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.8f).sp)
    val uriHandler = remember(onOpenLink) { object : UriHandler { override fun openUri(uri: String) = onOpenLink(uri) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("읽기 모드", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14) }, enabled = fontSize > 14) { Text("A−") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(22) }, enabled = fontSize < 22) { Text("A＋") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                SelectionContainer {
                    Markdown(
                        content = "# $title\n\n$markdown",
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 64.dp),
                        colors = markdownColor(codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                            tableBackground = MaterialTheme.colorScheme.surface, dividerColor = MaterialTheme.colorScheme.outlineVariant),
                        components = markdownComponents(
                            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
                            table = { model ->
                                // Learning notes must retain full definitions; the library defaults to single-line cells.
                                MarkdownTable(model.content, model.node, model.typography.table,
                                    headerBlock = { content, node, width, style ->
                                        MarkdownTableHeader(content, node, width, style, maxLines = Int.MAX_VALUE)
                                    },
                                    rowBlock = { content, node, width, style ->
                                        MarkdownTableRow(content, node, width, style, verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE)
                                    })
                            },
                        ),
                        typography = markdownTypography(
                            h1 = TextStyle(fontSize = (fontSize + 12).sp, lineHeight = (fontSize + 24).sp, fontWeight = FontWeight.Bold),
                            h2 = TextStyle(fontSize = (fontSize + 6).sp, lineHeight = (fontSize + 18).sp, fontWeight = FontWeight.SemiBold),
                            h3 = TextStyle(fontSize = (fontSize + 3).sp, lineHeight = (fontSize + 15).sp, fontWeight = FontWeight.SemiBold),
                            h4 = body.copy(fontWeight = FontWeight.Bold), h5 = body.copy(fontWeight = FontWeight.Bold), h6 = body.copy(fontWeight = FontWeight.Bold),
                            text = body, paragraph = body, ordered = body, bullet = body, list = body,
                            quote = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            table = body.copy(fontSize = (fontSize - 2).sp, lineHeight = (fontSize + 8).sp),
                            code = TextStyle(fontSize = (fontSize - 2).sp, lineHeight = (fontSize + 7).sp, fontFamily = FontFamily.Monospace),
                        ),
                        loading = { CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp)) },
                        error = { Text("이 문서를 표시하지 못했어요. 편집에서 원문을 확인해 주세요.", Modifier.padding(24.dp)) },
                    )
                }
            }
        }
    }
}
