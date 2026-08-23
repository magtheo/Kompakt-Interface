package dev.magnor.kompakt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** T-014: markdown parser for LLM replies — blocks, inline spans, previews. */
class MarkdownTest {

    // ---- inline ----

    @Test
    fun `plain text yields one plain span`() {
        assertEquals(listOf(MdSpan("just words", MdStyle.PLAIN)), parseInline("just words"))
    }

    @Test
    fun `bold italic and bold-italic spans`() {
        assertEquals(
            listOf(MdSpan("a ", MdStyle.PLAIN), MdSpan("b", MdStyle.BOLD), MdSpan(" c", MdStyle.PLAIN)),
            parseInline("a **b** c"),
        )
        assertEquals(
            listOf(MdSpan("x", MdStyle.ITALIC)),
            parseInline("*x*"),
        )
        assertEquals(
            listOf(MdSpan("bi", MdStyle.BOLD_ITALIC)),
            parseInline("***bi***"),
        )
    }

    @Test
    fun `code and strike spans`() {
        assertEquals(listOf(MdSpan("rm -rf", MdStyle.CODE)), parseInline("`rm -rf`"))
        assertEquals(listOf(MdSpan("old", MdStyle.STRIKE)), parseInline("~~old~~"))
    }

    @Test
    fun `link keeps label drops url`() {
        assertEquals(
            listOf(MdSpan("docs", MdStyle.LINK)),
            parseInline("[docs](https://example.com/a?b=1)"),
        )
    }

    @Test
    fun `unmatched markers stay literal`() {
        assertEquals(
            listOf(MdSpan("a ** b", MdStyle.PLAIN)),
            parseInline("a ** b"),
        )
        assertEquals(
            listOf(MdSpan("2 * 3 * 4", MdStyle.PLAIN)),
            parseInline("2 * 3 * 4"),
        )
    }

    @Test
    fun `snake_case words are not italicized`() {
        assertEquals(
            listOf(MdSpan("some_var_name", MdStyle.PLAIN)),
            parseInline("some_var_name"),
        )
    }

    @Test
    fun `inline across a sentence`() {
        assertEquals(
            listOf(
                MdSpan("Use ", MdStyle.PLAIN),
                MdSpan("npm install", MdStyle.CODE),
                MdSpan(" then ", MdStyle.PLAIN),
                MdSpan("restart", MdStyle.BOLD),
                MdSpan(".", MdStyle.PLAIN),
            ),
            parseInline("Use `npm install` then **restart**."),
        )
    }

    // ---- blocks ----

    @Test
    fun `consecutive lines join into one paragraph`() {
        val blocks = parseMarkdown("line one\nline two\n\nline three")
        assertEquals(2, blocks.size)
        assertEquals(
            MdBlock.Paragraph(listOf(MdSpan("line one line two", MdStyle.PLAIN))),
            blocks[0],
        )
        assertEquals(
            MdBlock.Paragraph(listOf(MdSpan("line three", MdStyle.PLAIN))),
            blocks[1],
        )
    }

    @Test
    fun `headings strip hash prefix and carry level`() {
        val blocks = parseMarkdown("# Title\n## Sub *head*\n### Deep")
        assertEquals(
            MdBlock.Heading(1, listOf(MdSpan("Title", MdStyle.PLAIN))),
            blocks[0],
        )
        assertEquals(
            MdBlock.Heading(2, listOf(MdSpan("Sub ", MdStyle.PLAIN), MdSpan("head", MdStyle.ITALIC))),
            blocks[1],
        )
        assertEquals(MdBlock.Heading(3, listOf(MdSpan("Deep", MdStyle.PLAIN))), blocks[2])
    }

    @Test
    fun `bullet ordered and task list items`() {
        val blocks = parseMarkdown(
            """
            - first
            * second with **bold**
            1. step one
            2) step two
            - [ ] todo item
            - [x] done item
            """.trimIndent(),
        )
        assertEquals(
            MdBlock.ListItem(0, "•", listOf(MdSpan("first", MdStyle.PLAIN))),
            blocks[0],
        )
        assertEquals(
            MdBlock.ListItem(
                0,
                "•",
                listOf(MdSpan("second with ", MdStyle.PLAIN), MdSpan("bold", MdStyle.BOLD)),
            ),
            blocks[1],
        )
        assertEquals(MdBlock.ListItem(0, "1.", listOf(MdSpan("step one", MdStyle.PLAIN))), blocks[2])
        assertEquals(MdBlock.ListItem(0, "2.", listOf(MdSpan("step two", MdStyle.PLAIN))), blocks[3])
        assertEquals(MdBlock.ListItem(0, "○", listOf(MdSpan("todo item", MdStyle.PLAIN))), blocks[4])
        assertEquals(MdBlock.ListItem(0, "●", listOf(MdSpan("done item", MdStyle.PLAIN))), blocks[5])
    }

    @Test
    fun `indented bullet gets nesting level`() {
        val blocks = parseMarkdown("- top\n  - nested\n      - deeper capped")
        assertEquals(0, (blocks[0] as MdBlock.ListItem).indent)
        assertEquals(1, (blocks[1] as MdBlock.ListItem).indent)
        // 6 leading spaces → level 3, capped at 3
        assertEquals(3, (blocks[2] as MdBlock.ListItem).indent)
    }

    @Test
    fun `fenced code block keeps lines verbatim and does not parse markdown`() {
        val blocks = parseMarkdown("before\n```kotlin\nval x = \"**not bold**\"\nprintln(x)\n```\nafter")
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("before", MdStyle.PLAIN))), blocks[0])
        assertEquals(
            MdBlock.CodeBlock(listOf("val x = \"**not bold**\"", "println(x)")),
            blocks[1],
        )
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("after", MdStyle.PLAIN))), blocks[2])
    }

    @Test
    fun `unclosed fence still yields code block`() {
        val blocks = parseMarkdown("```\nonly line")
        assertEquals(listOf(MdBlock.CodeBlock(listOf("only line"))), blocks)
    }

    @Test
    fun `quote lines merge into one quote block`() {
        val blocks = parseMarkdown("> quoted **strong**\n> more\nplain after")
        assertEquals(
            MdBlock.Quote(
                listOf(MdSpan("quoted ", MdStyle.PLAIN), MdSpan("strong", MdStyle.BOLD), MdSpan(" more", MdStyle.PLAIN)),
            ),
            blocks[0],
        )
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("plain after", MdStyle.PLAIN))), blocks[1])
    }

    @Test
    fun `thematic break variants become rules not bullets`() {
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("---"))
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("***"))
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("* * *"))
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("___"))
    }

    @Test
    fun `empty and blank input yields no blocks`() {
        assertEquals(emptyList<MdBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MdBlock>(), parseMarkdown("  \n \n"))
    }

    // ---- preview ----

    @Test
    fun `mdPreview strips markers and truncates`() {
        assertEquals("bold text here", mdPreview("**bold** text here", 40))
        assertEquals("• buy milk · • walk", mdPreview("- buy milk\n- walk", 40))
        assertEquals(
            "code line one",
            mdPreview("```\ncode line one\n```", 40),
        )
        assertEquals("abcde…", mdPreview("abcdefghij", 6))
    }

    @Test
    fun `mdPreview tolerates plain text`() {
        assertEquals("hello", mdPreview("hello", 10))
    }
}
