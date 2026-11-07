package com.xlollx.songport.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Accesso "dinamico" al JSON delle API: le risposte dei vari servizi hanno forme diverse
 * e cambiano spesso, quindi navighiamo l'albero invece di mappare classi rigide.
 */
val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

fun parseJson(text: String): JsonElement =
    if (text.isBlank()) JsonNull else json.parseToJsonElement(text)

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.doubleOrNull?.toLong()
val JsonElement?.int: Int? get() = long?.toInt()
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.booleanOrNull
val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull

/** Costruttore compatto di oggetti JSON per i body delle richieste. */
fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (k, v) -> k to toJson(v) }
)

fun jsonArr(items: List<Any?>): JsonArray = JsonArray(items.map { toJson(it) })

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
