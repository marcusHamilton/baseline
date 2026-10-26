package dev.baseline.data.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

// Garmin omits fields freely and sends explicit nulls. Every accessor maps both to null and never yields NaN/Infinity.

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.numberOrNull()

internal fun JsonObject.int(key: String): Int? = dbl(key)?.roundToInt()

internal fun JsonObject.lng(key: String): Long? = dbl(key)?.toLong()

internal fun JsonPrimitive.numberOrNull(): Double? =
    if (this is JsonNull) null else doubleOrNull?.takeIf { it.isFinite() }
