package dev.magnor.kompakt.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-022b: scratchpad/inbox section parsing — `## ` headings, em-dash title
 * split (V-058 sorter format), preamble untouched.
 */
class NoteSectionsTest {

    @Test
    fun `parses sections with em-dash titles and bodies`() {
        val text = """
            # Scratchpad

            ## 2026-08-25 09:12 — Call dentist
            Move to Thursday afternoon.

            ## 2026-08-24 21:03 — Idea: agent UI split
            Agents screen is a process manager.
        """.trimIndent()

        val sections = NoteSections.parse(text)

        assertEquals(2, sections.size)
        assertEquals("Call dentist", sections[0].title)
        assertEquals("Move to Thursday afternoon.", sections[0].body.trim())
        assertEquals("2026-08-25 09:12 — Call dentist", sections[0].heading)
        assertEquals("Idea: agent UI split", sections[1].title)
    }

    @Test
    fun `heading without em-dash keeps full heading as title`() {
        val sections = NoteSections.parse("## Plain heading\nBody line.")
        assertEquals(1, sections.size)
        assertEquals("Plain heading", sections[0].title)
    }

    @Test
    fun `preamble before first heading is not a section`() {
        val sections = NoteSections.parse("Intro text\n## First\nbody")
        assertEquals(1, sections.size)
        assertEquals("First", sections[0].title)
    }

    @Test
    fun `text without headings has no sections`() {
        assertTrue(NoteSections.parse("Just a plain note.").isEmpty())
    }

    @Test
    fun `h3 and deeper headings belong to the section body`() {
        val sections = NoteSections.parse("## Outer\n### Inner\ncontent")
        assertEquals(1, sections.size)
        assertTrue(sections[0].body.contains("### Inner"))
    }

    @Test
    fun `remove drops only the targeted section`() {
        val text = "# Scratchpad\n\n## A — first\nbody a\n\n## B — second\nbody b\n\n## C — third\nbody c"
        val sections = NoteSections.parse(text)
        val withoutSecond = NoteSections.remove(text, sections[1])

        assertEquals(
            "# Scratchpad\n\n## A — first\nbody a\n\n## C — third\nbody c",
            withoutSecond,
        )
        val resected = NoteSections.parse(withoutSecond)
        assertEquals(listOf("first", "third"), resected.map { it.title })
    }

    @Test
    fun `remove first section keeps preamble`() {
        val text = "Preamble\n\n## A — first\nbody a\n\n## B — second\nbody b"
        val withoutFirst = NoteSections.remove(text, NoteSections.parse(text)[0])
        assertEquals("Preamble\n\n## B — second\nbody b", withoutFirst)
    }

    @Test
    fun `remove last section leaves no trailing blank run`() {
        val text = "## A — first\nbody a\n\n## B — last\nbody b"
        val withoutLast = NoteSections.remove(text, NoteSections.parse(text)[1])
        assertEquals("## A — first\nbody a", withoutLast)
    }
}
