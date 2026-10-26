package dev.baseline.data.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Loads src/test/resources/fixtures/[name]. */
fun fixture(name: String): JsonElement {
    val text = checkNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
    return Json.parseToJsonElement(text)
}
