package dev.baseline.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseNotesTest {
    private val good =
        """
        One-tap updates, plus a fix for sleep times.

        ## New
        - Update in place from **Today**
        ## Fixed
        - Sleep times follow the [time zone](https://x)
        """.trimIndent()

    @Test
    fun houseFormatIsParsedIntoASummaryAndSections() {
        val n = parseNotes(good)
        assertEquals("One-tap updates, plus a fix for sleep times.", n.summary)
        assertEquals(listOf("New", "Fixed"), n.sections.map { it.title })
        assertEquals(listOf("Update in place from Today"), n.sections[0].items)
        assertEquals(listOf("Sleep times follow the time zone"), n.sections[1].items)
        assertEquals(emptyList<String>(), notesProblems(good.replace("](https://x)", "]").replace("[time", "time")))
    }

    @Test
    fun anOldStyleParagraphStillShowsAsPlainText() {
        val n = parseNotes("Adds five home-screen widgets: **Readiness**, Vitals.\r\nThey refresh after each sync.")
        assertEquals(emptyList<NotesSection>(), n.sections)
        assertEquals("Adds five home-screen widgets: Readiness, Vitals.\nThey refresh after each sync.", n.summary)
    }

    @Test
    fun aWrappedBulletIsJoinedIntoOne() {
        val n = parseNotes("Sum.\n\n## New\n- First half\n  second half")
        assertEquals(listOf("First half second half"), n.sections.single().items)
    }

    @Test
    fun theStandardRejectsWhatItForbids() {
        fun problems(vararg lines: String) = notesProblems(lines.joinToString("\n")).joinToString(" | ")
        assertTrue(problems("Sum.", "## Fixed", "- Crash on open (#12)").contains("no PR or issue numbers"))
        assertTrue(problems("Sum.", "## Fixed", "- Fixed in 3f9c2ab1").contains("no commit hashes"))
        assertTrue(problems("Sum.", "## Fixed", "- See https://example.com").contains("no links"))
        assertTrue(problems("Sum.", "## Fixed", "- Sub", "  - nested").contains("no nested bullets"))
        assertTrue(problems("Sum.", "## Fixed", "```", "code", "```").contains("no code blocks"))
        assertTrue(problems("Sum.", "## Changes", "- Something").contains("must be one of New, Improved, Fixed"))
        assertTrue(problems("Sum.", "## New", "- A", "## New", "- B").contains("each section appears once"))
        assertTrue(problems("Sum.", "## New", "Plain text, not a bullet").contains("must be a '- ' bullet"))
        assertTrue(problems("# Release 0.05", "Sum.", "## New", "- A").contains("headings are"))
        assertTrue(problems("First line.", "Second line.", "## New", "- A").contains("the summary is one line"))
        assertTrue(problems("## New", "- A").contains("needs a one-line summary"))
        assertTrue(problems("Just a paragraph with no sections").contains("needs at least one"))
        assertTrue(problems("S".repeat(141), "## New", "- A").contains("summary is 141 characters"))
        assertTrue(problems("Sum.", "## New", "- " + "b".repeat(91)).contains("bullet is 91 characters"))
        assertTrue(problems("Sum.", "## New", "- 1", "- 2", "- 3", "- 4", "- 5", "- 6").contains("has 6 bullets"))
    }

    @Test
    fun everyReleaseNotesFileFollowsTheStandard() {
        // Unit tests run in app/, one level below the repo root.
        val files = File("../release-notes").listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }
        assertTrue("release-notes/ has no notes files", files.isNotEmpty())
        for (f in files) {
            assertTrue("${f.name} must be named vX.YY.md", Regex("""v\d+\.\d{2}\.md""").matches(f.name))
            assertEquals("${f.name} breaks docs/release-notes.md", emptyList<String>(), notesProblems(f.readText()))
        }
    }
}
