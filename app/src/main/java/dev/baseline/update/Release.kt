package dev.baseline.update

import dev.baseline.data.parse.arr
import dev.baseline.data.parse.lng
import dev.baseline.data.parse.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A GitHub release the app can install: its tag, notes and its one APK asset. [sha256] is lowercase hex, or null if GitHub sent no digest. */
data class Release(val tag: String, val notes: String, val pageUrl: String, val apkUrl: String, val apkSize: Long, val sha256: String?) {
    /** "0.05" for "v0.05": what the UI shows. */
    val version: String get() = tag.removePrefix("v")
}

sealed interface ParsedRelease {
    data class Ok(val release: Release) : ParsedRelease

    /** Readable, logged and shown as "couldn't check"; never contains anything secret (the API is unauthenticated). */
    data class Unusable(val reason: String) : ParsedRelease
}

/** Parses `GET /repos/{owner}/{repo}/releases/latest`. A release needs exactly one `.apk` asset (spec: in-app updates §4). */
fun parseRelease(body: String): ParsedRelease {
    val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ParsedRelease.Unusable("Unreadable release")
    val tag = o.str("tag_name") ?: return ParsedRelease.Unusable("Release has no tag")
    if (o.flag("draft") || o.flag("prerelease")) return ParsedRelease.Unusable("Latest release is a draft or pre-release")
    val apks = o.arr("assets").orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
    val apk = apks.singleOrNull() ?: return ParsedRelease.Unusable("Release $tag has ${apks.size} APK files, not 1")
    val url = apk.str("browser_download_url") ?: return ParsedRelease.Unusable("Release $tag has no download link")
    val size = apk.lng("size")?.takeIf { it > 0 } ?: return ParsedRelease.Unusable("Release $tag has no APK size")
    val sha = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 }
    return ParsedRelease.Ok(Release(tag, plainNotes(o.str("body").orEmpty()), o.str("html_url") ?: url, url, size, sha))
}

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** "v0.05" or "0.05-fake" -> [0, 5]; null if it isn't dot-separated numbers. The suffix after '-' is ignored. */
internal fun versionParts(name: String): List<Int>? {
    val parts = name.trim().removePrefix("v").substringBefore('-').split('.')
    return parts.map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
}

/** True when [tag] is a later version than [installed], compared number by number ("0.10" > "0.09"). Unparseable -> false. */
fun isNewer(tag: String, installed: String): Boolean {
    val a = versionParts(tag) ?: return false
    val b = versionParts(installed) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private val Link = Regex("""\[([^\]]*)]\([^)]*\)""")
private val Emphasis = Regex("""\*\*|__|`""")

/** Release notes are Markdown; the app shows them as plain text: no heading marks, bullets as "•", links as their text. */
fun plainNotes(markdown: String): String = markdown
    .replace("\r\n", "\n")
    .lines()
    .map { line ->
        val t = line.trimEnd().replace(Link, "$1").replace(Emphasis, "")
        when {
            t.trimStart().startsWith("#") -> t.trimStart().trimStart('#').trim()
            t.trimStart().startsWith("- ") || t.trimStart().startsWith("* ") -> "• " + t.trimStart().drop(2)
            else -> t
        }
    }.joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/** Whole percent of [total], 0..100; 0 while the size is unknown. Shared by the UI and the download notification. */
fun percentOf(done: Long, total: Long): Int = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

/** Decimal megabytes, rounded, as Android's own storage screens count them. */
fun megabytes(bytes: Long): Long = (bytes + 500_000) / 1_000_000
