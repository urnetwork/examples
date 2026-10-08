// Readers for the JSON values the app handles: the C ABI's status values, the
// token server's answers and the cap objects. kotlinx.serialization's JSON
// elements only; no serialization compiler plugin. Each reader throws
// IllegalArgumentException (kotlinx.serialization's SerializationException is
// one) for a value of the wrong type.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The JSON object of a text: null for null text and for JSON null. Throws IllegalArgumentException
 * for text that is not JSON or another JSON value.
 */
fun parseNullableJsonObject(text: String?): JsonObject? {
    if (text == null) {
        return null
    }
    return when (val element = Json.parseToJsonElement(text)) {
        is JsonNull -> null
        is JsonObject -> element
        else -> throw IllegalArgumentException("not a JSON object")
    }
}

/** A string member; null when it is absent or null. */
fun JsonObject.stringMember(name: String): String? =
    when (val value = this[name]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw IllegalArgumentException("$name is not a string")
        else -> throw IllegalArgumentException("$name is not a string")
    }

/** An integer member that fits a Long; null when it is absent or null. */
fun JsonObject.nullableLongMember(name: String): Long? =
    when (val value = this[name]) {
        null, is JsonNull -> null
        is JsonPrimitive ->
            if (value.isString) throw IllegalArgumentException("$name is not an integer")
            else value.longOrNull ?: throw IllegalArgumentException("$name is not an integer")
        else -> throw IllegalArgumentException("$name is not an integer")
    }

/** An integer member that fits a Long; 0 when it is absent or null. */
fun JsonObject.longMember(name: String): Long = nullableLongMember(name) ?: 0

/** A boolean member; false when it is absent or null. */
fun JsonObject.booleanMember(name: String): Boolean =
    when (val value = this[name]) {
        null, is JsonNull -> false
        is JsonPrimitive ->
            if (value.isString) throw IllegalArgumentException("$name is not a boolean")
            else value.booleanOrNull ?: throw IllegalArgumentException("$name is not a boolean")
        else -> throw IllegalArgumentException("$name is not a boolean")
    }

/** An object member; null when it is absent or null. */
fun JsonObject.objectMember(name: String): JsonObject? =
    when (val value = this[name]) {
        null, is JsonNull -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("$name is not an object")
    }
