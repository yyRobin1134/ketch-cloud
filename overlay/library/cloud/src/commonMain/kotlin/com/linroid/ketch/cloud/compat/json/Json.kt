package com.linroid.ketch.cloud.compat.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A small, behaviour-compatible stand-in for Android's `org.json`, so code written against it
 * (the YunX share-link parsers) runs unchanged on Kotlin/Native. Coercion rules follow
 * Android's `JSON` helper class: `opt*` never throws, `get*` throws [JSONException].
 */
class JSONException(message: String?) : Exception(message)

private object NullValue {
  override fun toString(): String = "null"
  override fun equals(other: Any?): Boolean = other == null || other === this
  override fun hashCode(): Int = 0
}

private object Coerce {
  fun toBoolean(value: Any?): Boolean? = when (value) {
    is Boolean -> value
    is String -> when {
      value.equals("true", ignoreCase = true) -> true
      value.equals("false", ignoreCase = true) -> false
      else -> null
    }
    else -> null
  }

  fun toDouble(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
  }

  fun toInt(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.toDoubleOrNull()?.toInt()
    else -> null
  }

  fun toLong(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toDoubleOrNull()?.toLong()
    else -> null
  }

  fun toText(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    else -> value.toString()
  }
}

private fun typeMismatch(key: Any?, actual: Any?, wanted: String): JSONException =
  JSONException(
    if (actual == null) "Value at $key is null."
    else "Value $actual at $key of type ${actual::class.simpleName} cannot be converted to $wanted",
  )

private fun fromElement(element: JsonElement): Any = when (element) {
  is JsonNull -> JSONObject.NULL
  is JsonObject -> JSONObject().also { obj ->
    for ((k, v) in element) obj.putRaw(k, fromElement(v))
  }
  is JsonArray -> JSONArray().also { arr -> for (v in element) arr.putRaw(fromElement(v)) }
  is JsonPrimitive -> when {
    element.isString -> element.content
    element.content == "true" -> true
    element.content == "false" -> false
    else -> parseNumber(element.content)
  }
}

private fun parseNumber(text: String): Any {
  if (text.contains('.') || text.contains('e') || text.contains('E')) {
    return text.toDoubleOrNull() ?: text
  }
  val long = text.toLongOrNull() ?: return text.toDoubleOrNull() ?: text
  return if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
}

private val lenientJson = Json { isLenient = true; allowSpecialFloatingPointLiterals = true }

private fun parse(text: String): Any {
  val element = try {
    lenientJson.parseToJsonElement(text.trim())
  } catch (e: Exception) {
    throw JSONException(e.message)
  }
  return fromElement(element)
}

private fun StringBuilder.appendQuoted(text: String) {
  append('"')
  for (c in text) {
    when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      else -> if (c < ' ') {
        append("\\u").append(c.code.toString(16).padStart(4, '0'))
      } else {
        append(c)
      }
    }
  }
  append('"')
}

private fun StringBuilder.writeValue(value: Any?) {
  when (value) {
    null -> append("null")
    is String -> appendQuoted(value)
    is JSONObject -> value.writeTo(this)
    is JSONArray -> value.writeTo(this)
    is Double ->
      append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
    else -> append(value.toString())
  }
}

/** Maps Kotlin collections the way `org.json` does when they are put into a container. */
private fun normalise(value: Any?): Any? = when (value) {
  is Map<*, *> -> JSONObject(value)
  is Collection<*> -> JSONArray(value)
  is Array<*> -> JSONArray(value.toList())
  is Float -> value.toDouble()
  is Short -> value.toInt()
  is Byte -> value.toInt()
  else -> value
}

class JSONTokener(private val text: String) {
  /** Parses the whole text; unlike Android's tokener it cannot read a value piece by piece. */
  fun nextValue(): Any = parse(text)
}

class JSONObject {
  private val map = LinkedHashMap<String, Any?>()

  constructor()

  constructor(json: String) {
    val value = parse(json)
    if (value !is JSONObject) {
      throw JSONException("Value $value of type ${value::class.simpleName} cannot be converted to JSONObject")
    }
    map.putAll(value.map)
  }

  constructor(source: Map<*, *>) {
    for ((k, v) in source) put(k.toString(), v)
  }

  internal fun putRaw(name: String, value: Any?) {
    map[name] = value
  }

  fun length(): Int = map.size

  fun has(name: String?): Boolean = map.containsKey(name)

  fun isNull(name: String?): Boolean = map[name].let { it == null || it === NULL }

  fun opt(name: String?): Any? = map[name]

  fun get(name: String): Any = map[name] ?: throw JSONException("No value for $name")

  fun keys(): Iterator<String> = map.keys.toList().iterator()

  fun names(): JSONArray? =
    if (map.isEmpty()) null else JSONArray().also { arr -> map.keys.forEach { arr.put(it) } }

  fun remove(name: String?): Any? = map.remove(name)

  fun put(name: String, value: Any?): JSONObject = apply {
    if (value == null) map.remove(name) else map[name] = normalise(value)
  }

  fun put(name: String, value: Boolean): JSONObject = put(name, value as Any?)
  fun put(name: String, value: Int): JSONObject = put(name, value as Any?)
  fun put(name: String, value: Long): JSONObject = put(name, value as Any?)
  fun put(name: String, value: Double): JSONObject = put(name, value as Any?)

  fun putOpt(name: String?, value: Any?): JSONObject = apply {
    if (name != null && value != null) put(name, value)
  }

  fun getBoolean(name: String): Boolean =
    Coerce.toBoolean(get(name)) ?: throw typeMismatch(name, map[name], "boolean")

  fun optBoolean(name: String?, fallback: Boolean = false): Boolean =
    Coerce.toBoolean(map[name]) ?: fallback

  fun getInt(name: String): Int =
    Coerce.toInt(get(name)) ?: throw typeMismatch(name, map[name], "int")

  fun optInt(name: String?, fallback: Int = 0): Int = Coerce.toInt(map[name]) ?: fallback

  fun getLong(name: String): Long =
    Coerce.toLong(get(name)) ?: throw typeMismatch(name, map[name], "long")

  fun optLong(name: String?, fallback: Long = 0L): Long = Coerce.toLong(map[name]) ?: fallback

  fun getDouble(name: String): Double =
    Coerce.toDouble(get(name)) ?: throw typeMismatch(name, map[name], "double")

  fun optDouble(name: String?, fallback: Double = Double.NaN): Double =
    Coerce.toDouble(map[name]) ?: fallback

  fun getString(name: String): String =
    Coerce.toText(get(name)) ?: throw typeMismatch(name, map[name], "String")

  fun optString(name: String?, fallback: String = ""): String =
    Coerce.toText(map[name]) ?: fallback

  fun getJSONObject(name: String): JSONObject =
    get(name) as? JSONObject ?: throw typeMismatch(name, map[name], "JSONObject")

  fun optJSONObject(name: String?): JSONObject? = map[name] as? JSONObject

  fun getJSONArray(name: String): JSONArray =
    get(name) as? JSONArray ?: throw typeMismatch(name, map[name], "JSONArray")

  fun optJSONArray(name: String?): JSONArray? = map[name] as? JSONArray

  internal fun writeTo(out: StringBuilder) {
    out.append('{')
    var first = true
    for ((k, v) in map) {
      if (!first) out.append(',')
      first = false
      out.appendQuoted(k)
      out.append(':')
      out.writeValue(v)
    }
    out.append('}')
  }

  override fun toString(): String = buildString { writeTo(this) }

  companion object {
    /** Marks an explicit JSON `null`, distinct from a missing key. */
    val NULL: Any = NullValue

    fun quote(text: String?): String =
      if (text == null) "\"\"" else buildString { appendQuoted(text) }
  }
}

class JSONArray {
  private val list = ArrayList<Any?>()

  constructor()

  constructor(json: String) {
    val value = parse(json)
    if (value !is JSONArray) {
      throw JSONException("Value $value of type ${value::class.simpleName} cannot be converted to JSONArray")
    }
    list.addAll(value.list)
  }

  constructor(source: Collection<*>) {
    source.forEach { put(it) }
  }

  internal fun putRaw(value: Any?) {
    list.add(value)
  }

  fun length(): Int = list.size

  fun isNull(index: Int): Boolean =
    list.getOrNull(index).let { it == null || it === JSONObject.NULL }

  fun opt(index: Int): Any? = list.getOrNull(index)

  fun get(index: Int): Any =
    list.getOrNull(index) ?: throw JSONException("Index $index out of range [0..${list.size})")

  fun put(value: Any?): JSONArray = apply { list.add(normalise(value ?: JSONObject.NULL)) }
  fun put(value: Boolean): JSONArray = put(value as Any?)
  fun put(value: Int): JSONArray = put(value as Any?)
  fun put(value: Long): JSONArray = put(value as Any?)
  fun put(value: Double): JSONArray = put(value as Any?)

  fun put(index: Int, value: Any?): JSONArray = apply {
    while (list.size <= index) list.add(JSONObject.NULL)
    list[index] = normalise(value ?: JSONObject.NULL)
  }

  fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

  fun getBoolean(index: Int): Boolean =
    Coerce.toBoolean(get(index)) ?: throw typeMismatch(index, list[index], "boolean")

  fun optBoolean(index: Int, fallback: Boolean = false): Boolean =
    Coerce.toBoolean(opt(index)) ?: fallback

  fun getInt(index: Int): Int =
    Coerce.toInt(get(index)) ?: throw typeMismatch(index, list[index], "int")

  fun optInt(index: Int, fallback: Int = 0): Int = Coerce.toInt(opt(index)) ?: fallback

  fun getLong(index: Int): Long =
    Coerce.toLong(get(index)) ?: throw typeMismatch(index, list[index], "long")

  fun optLong(index: Int, fallback: Long = 0L): Long = Coerce.toLong(opt(index)) ?: fallback

  fun getDouble(index: Int): Double =
    Coerce.toDouble(get(index)) ?: throw typeMismatch(index, list[index], "double")

  fun optDouble(index: Int, fallback: Double = Double.NaN): Double =
    Coerce.toDouble(opt(index)) ?: fallback

  fun getString(index: Int): String =
    Coerce.toText(get(index)) ?: throw typeMismatch(index, list[index], "String")

  fun optString(index: Int, fallback: String = ""): String =
    Coerce.toText(opt(index)) ?: fallback

  fun getJSONObject(index: Int): JSONObject =
    get(index) as? JSONObject ?: throw typeMismatch(index, list[index], "JSONObject")

  fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

  fun getJSONArray(index: Int): JSONArray =
    get(index) as? JSONArray ?: throw typeMismatch(index, list[index], "JSONArray")

  fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

  fun join(separator: String): String =
    list.joinToString(separator) { buildString { writeValue(it) } }

  internal fun writeTo(out: StringBuilder) {
    out.append('[')
    list.forEachIndexed { i, v ->
      if (i > 0) out.append(',')
      out.writeValue(v)
    }
    out.append(']')
  }

  override fun toString(): String = buildString { writeTo(this) }
}
