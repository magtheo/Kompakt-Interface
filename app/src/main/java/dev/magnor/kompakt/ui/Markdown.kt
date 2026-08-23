package dev.magnor.kompakt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.cards.CardMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * Minimal markdown renderer for LLM replies (T-014). Dependency-free,
 * e-ink safe: styling via weight / family / decoration / alignment only,
 * never color (D017 monochrome rules).
 *
 * Supported blocks: paragraphs, `#` headings, bullet / ordered / task
 * lists (with one-level-per-2-spaces nesting), fenced code blocks,
 * `>` quotes, thematic breaks. Inline: **bold**, *italic*, ***both***,
 * `code`, ~~strike~~, [label](url) links (label kept, underlined).
 *
 * Deliberately NOT supported: `_underscore_` emphasis (snake_case
 * identifiers are common in agent/chat prose and would be mangled),
 * nested inline styles, tables (rendered as plain paragraphs), images.
 */

enum class MdStyle { PLAIN, BOLD, ITALIC, BOLD_ITALIC, CODE, STRIKE, LINK }

data class MdSpan(val text: String, val style: MdStyle = MdStyle.PLAIN)

sealed class MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class ListItem(val indent: Int, val marker: String, val spans: List<MdSpan>) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class CodeBlock(val lines: List<String>) : MdBlock()
    data object Rule : MdBlock()
}

private val headingPattern = Regex("^#{1,6}\\s+")
private val bulletPattern = Regex("^([-*+])\\s+")
private val orderedPattern = Regex("^(\\d{1,3})[.)]\\s+")
private val rulePattern = Regex("^([-*_])(\\s*\\1){2,}$")
private val taskOpenPattern = Regex("^\\[\\s?]\\s")
private val taskDonePattern = Regex("^\\[[xX]]\\s")

// Order matters: *** before ** before * so bold-italic wins at the same position.
// Emphasis content must start/end with a non-space char (CommonMark rule that
// keeps "2 * 3 * 4" plain).
private val inlinePattern = Regex(
    """(`[^`\n]+`)|(\*\*\*\S(?:[^*\n]*\S)?\*\*\*)|(\*\*\S(?:[^*\n]*\S)?\*\*)|(\*\S(?:[^*\n]*\S)?\*)|(~~\S(?:[^~\n]*\S)?~~)|(\[[^\]\n]+\]\([^)\s]+\))""",
)

/** Split raw LLM text into typed blocks. Pure function — unit tested. */
fun parseMarkdown(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val paragraph = StringBuilder()
    val quote = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        paragraph.clear()
        if (text.isNotEmpty()) blocks += MdBlock.Paragraph(parseInline(text))
    }

    fun flushQuote() {
        val text = quote.toString().trim()
        quote.clear()
        if (text.isNotEmpty()) blocks += MdBlock.Quote(parseInline(text))
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph()
                flushQuote()
                val fence = trimmed.take(3)
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    code += lines[i]
                    i++
                }
                // i is now the closing fence (or past the end) — skipped below.
                if (code.isNotEmpty()) blocks += MdBlock.CodeBlock(code)
            }
            trimmed.isEmpty() -> {
                flushParagraph()
                flushQuote()
            }
            rulePattern.matches(trimmed) -> {
                flushParagraph()
                flushQuote()
                blocks += MdBlock.Rule
            }
            headingPattern.containsMatchIn(trimmed) -> {
                flushParagraph()
                flushQuote()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MdBlock.Heading(level, parseInline(trimmed.dropWhile { it == '#' }.trim()))
            }
            bulletPattern.containsMatchIn(trimmed) || orderedPattern.containsMatchIn(trimmed) -> {
                flushParagraph()
                flushQuote()
                val indent = (line.length - line.trimStart().length) / 2
                val ordered = orderedPattern.find(trimmed)
                val marker: String
                val text: String
                if (ordered != null) {
                    marker = "${ordered.groupValues[1]}."
                    text = trimmed.substring(ordered.value.length)
                } else {
                    val bullet = bulletPattern.find(trimmed)!!
                    val afterBullet = trimmed.substring(bullet.value.length)
                    marker = when {
                        taskDonePattern.containsMatchIn(afterBullet) -> "●"
                        taskOpenPattern.containsMatchIn(afterBullet) -> "○"
                        else -> "•"
                    }
                    text = afterBullet
                        .replaceFirst(taskOpenPattern, "")
                        .replaceFirst(taskDonePattern, "")
                }
                blocks += MdBlock.ListItem(
                    indent = indent.coerceIn(0, 3),
                    marker = marker,
                    spans = parseInline(text.trim()),
                )
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(trimmed.dropWhile { it == '>' }.trim())
            }
            else -> {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flushParagraph()
    flushQuote()
    return blocks
}

/** Split one text run into styled spans. Unmatched markers stay literal. */
fun parseInline(text: String): List<MdSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<MdSpan>()
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        if (match.range.first > cursor) {
            spans += MdSpan(text.substring(cursor, match.range.first))
        }
        val g = match.groups
        spans += when {
            g[1] != null -> MdSpan(match.value.substring(1, match.value.length - 1), MdStyle.CODE)
            g[2] != null -> MdSpan(match.value.substring(3, match.value.length - 3), MdStyle.BOLD_ITALIC)
            g[3] != null -> MdSpan(match.value.substring(2, match.value.length - 2), MdStyle.BOLD)
            g[4] != null -> MdSpan(match.value.substring(1, match.value.length - 1), MdStyle.ITALIC)
            g[5] != null -> MdSpan(match.value.substring(2, match.value.length - 2), MdStyle.STRIKE)
            else -> MdSpan(match.value.substring(1, match.value.indexOf("](")), MdStyle.LINK)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) spans += MdSpan(text.substring(cursor))
    return spans
}

/**
 * Plain-text excerpt for previews (jump index, thread list) with markdown
 * markers stripped: `**bold** here` → `bold here`.
 */
fun mdPreview(raw: String, maxChars: Int): String {
    val plain = parseMarkdown(raw).joinToString(" · ") { block ->
        when (block) {
            is MdBlock.Paragraph -> block.spans.joinToString("") { it.text }
            is MdBlock.Heading -> block.spans.joinToString("") { it.text }
            is MdBlock.ListItem -> "${block.marker} ${block.spans.joinToString("") { it.text }}"
            is MdBlock.Quote -> block.spans.joinToString("") { it.text }
            is MdBlock.CodeBlock -> block.lines.joinToString(" ")
            MdBlock.Rule -> "—"
        }
    }
    return if (plain.length <= maxChars) plain else plain.take(maxChars - 1).trimEnd() + "…"
}

private fun annotated(spans: List<MdSpan>, base: FontWeight?): AnnotatedString =
    buildAnnotatedString {
        val baseStyle = SpanStyle(fontWeight = base)
        spans.forEach { span ->
            val style = when (span.style) {
                MdStyle.PLAIN -> baseStyle
                MdStyle.BOLD -> baseStyle.copy(fontWeight = FontWeight.Bold)
                MdStyle.ITALIC -> baseStyle.copy(fontStyle = FontStyle.Italic)
                MdStyle.BOLD_ITALIC -> baseStyle.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                MdStyle.CODE -> baseStyle.copy(fontFamily = FontFamily.Monospace)
                MdStyle.STRIKE -> baseStyle.copy(textDecoration = TextDecoration.LineThrough)
                MdStyle.LINK -> baseStyle.copy(textDecoration = TextDecoration.Underline)
            }
            withStyle(style) { append(span.text) }
        }
    }

/**
 * Renders [raw] markdown. Monochrome styling only: weight, family,
 * decoration, size and layout — no colors, no animation (e-ink, D017).
 */
@Composable
fun MarkdownText(
    raw: String,
    modifier: Modifier = Modifier,
    baseFontWeight: FontWeight? = null,
    bodyFontSize: TextUnit = TextUnit.Unspecified,
    bodyLineHeight: TextUnit = 21.sp,
) {
    val blocks = remember(raw) { parseMarkdown(raw) }
    if (blocks.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> TextMMD(
                    text = annotated(block.spans, baseFontWeight),
                    fontSize = bodyFontSize,
                    lineHeight = bodyLineHeight,
                )
                is MdBlock.Heading -> TextMMD(
                    text = annotated(block.spans, FontWeight.Bold),
                    fontSize = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        else -> 15.sp
                    },
                    lineHeight = bodyLineHeight,
                )
                is MdBlock.ListItem -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (12 * block.indent).dp),
                ) {
                    TextMMD(
                        text = block.marker,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(22.dp),
                    )
                    TextMMD(
                        text = annotated(block.spans, baseFontWeight),
                        fontSize = bodyFontSize,
                        lineHeight = bodyLineHeight,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                }
                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                    TextMMD(
                        text = annotated(block.spans, baseFontWeight),
                        fontStyle = FontStyle.Italic,
                        fontSize = bodyFontSize,
                        lineHeight = bodyLineHeight,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                }
                is MdBlock.CodeBlock -> CardMMD(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        block.lines.forEach { codeLine ->
                            TextMMD(
                                text = codeLine.ifEmpty { " " },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        }
    }
}
