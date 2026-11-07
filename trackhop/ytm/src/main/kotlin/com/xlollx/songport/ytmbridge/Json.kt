package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Dynamic navigation of YouTube Music responses: their shape changes often, so we walk the tree. */
val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

fun parseJson(text: String): JsonElement = if (text.isBlank()) JsonNull else json.parseToJsonElement(text)

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull

/** Every value stored under [key], anywhere in the tree, in document order. */
fun JsonElement?.findAll(key: String): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    fun walk(e: JsonElement?) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (k == key) out += v; walk(v) }
            is JsonArray -> e.forEach { walk(it) }
            else -> {}
        }
    }
    walk(this)
    return out
}

/** First value stored under [key], depth first. */
fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> {
            this[key]?.let { return it }
            for (v in values) v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> {}
    }
    return null
}

/** Text of a `{ "runs": [ {"text": ...}, ... ] }` block. */
fun JsonElement?.runsText(): String? =
    this["runs"].arr.mapNotNull { it["text"].str }.joinToString("").ifBlank { null }

fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to toJson(v) })

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is List<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
