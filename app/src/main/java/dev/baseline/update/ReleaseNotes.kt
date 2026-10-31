package dev.baseline.update

/** One headed group of bullets in a release's notes. */
data class NotesSection(val title: String, val items: List<String>)

/**
 * A release's notes in the house format (docs/release-notes.md): one summary line, then New / Improved / Fixed bullets.
 * Notes that don't follow it (older releases) come back as a [summary] of plain text with no [sections].
 */
data class ReleaseNotes(val summary: String, val sections: List<NotesSection>)

val NOTES_SECTIONS = listOf("New", "Improved", "Fixed")
const val NOTES_SUMMARY_MAX = 140
const val NOTES_SECTION_MAX = 5
const val NOTES_BULLET_MAX = 90

fun parseNotes(markdown: String): ReleaseNotes {
    val summary = mutableListOf<String>()
    val sections = mutableListOf<Pair<String, MutableList<String>>>()
    for (raw in markdown.replace("\r\n", "\n").lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#") -> sections += line.trimStart('#').trim() to mutableListOf()
            sections.isEmpty() -> summary += stripInline(line)
            line.startsWith("- ") || line.startsWith("* ") -> sections.last().second += stripInline(line.drop(2).trim())
            else -> sections.last().second.let { if (it.isEmpty()) it += stripInline(line) else it[it.lastIndex] = it.last() + " " + stripInline(line) }
        }
    }
    val filled = sections.filter { it.second.isNotEmpty() }
    if (filled.isEmpty()) return ReleaseNotes(plainNotes(markdown), emptyList())
    return ReleaseNotes(summary.joinToString(" "), filled.map { NotesSection(it.first, it.second) })
}

private val Forbidden = listOf(
    Regex("```") to "no code blocks",
    Regex("""!\[""") to "no images",
    Regex("""]\(|https?://""") to "no links (the release page has them)",
    Regex("""^\s*\|""") to "no tables",
    Regex("""^\s+[-*] """) to "no nested bullets",
    Regex("""(?<![\w&])#\d+""") to "no PR or issue numbers",
    Regex("""\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\b""") to "no commit hashes",
)

/** What is wrong with [markdown] against docs/release-notes.md; empty when it follows the format. Checked on every file in release-notes/. */
fun notesProblems(markdown: String): List<String> {
    val problems = mutableListOf<String>()
    val notes = parseNotes(markdown)
    if (notes.sections.isEmpty()) problems += "needs at least one '## New', '## Improved' or '## Fixed' section with '- ' bullets"
    if (notes.summary.isBlank()) problems += "needs a one-line summary before the first section"
    if (notes.summary.length > NOTES_SUMMARY_MAX) problems += "summary is ${notes.summary.length} characters (max $NOTES_SUMMARY_MAX)"
    notes.sections.forEach { s ->
        if (s.title !in NOTES_SECTIONS) problems += "section '${s.title}' must be one of ${NOTES_SECTIONS.joinToString()}"
        if (s.items.size > NOTES_SECTION_MAX) problems += "'${s.title}' has ${s.items.size} bullets (max $NOTES_SECTION_MAX)"
        s.items.filter { it.length > NOTES_BULLET_MAX }.forEach { problems += "bullet is ${it.length} characters (max $NOTES_BULLET_MAX): '${it.take(30)}…'" }
    }
    if (notes.sections.map { it.title }.let { it.size != it.toSet().size }) problems += "each section appears once"

    var inSection = false
    var summaryLines = 0
    for (line in markdown.replace("\r\n", "\n").lines().filter { it.isNotBlank() }) {
        Forbidden.firstOrNull { it.first.containsMatchIn(line) }?.let { problems += "${it.second}: '${line.trim().take(30)}'" }
        when {
            line.startsWith("## ") -> inSection = true
            line.startsWith("#") -> problems += "headings are '## New', '## Improved' or '## Fixed': '${line.take(30)}'"
            !inSection -> summaryLines++
            !line.startsWith("- ") -> problems += "text under a heading must be a '- ' bullet: '${line.trim().take(30)}'"
        }
    }
    if (summaryLines > 1) problems += "the summary is one line"
    return problems.distinct()
}
