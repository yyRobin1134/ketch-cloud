package com.linroid.ketch.cloud.compat.util

/**
 * `java.net.URI` for the parts the YunX code uses: component accessors and RFC 3986
 * reference resolution. Like the JDK class it throws [IllegalArgumentException] for text that
 * is not a valid URI, and `host` is null when there is no (valid) server-based authority.
 */
class URI(private val text: String) {
  val scheme: String?
  val rawAuthority: String?
  val rawPath: String
  val rawQuery: String?
  val rawFragment: String?

  init {
    require(text.none { it == ' ' || it == '\n' || it == '\r' || it == '\t' || it == '"' || it == '<' || it == '>' }) {
      "Illegal character in URI: $text"
    }
    val m = PATTERN.matchEntire(text) ?: throw IllegalArgumentException("Invalid URI: $text")
    scheme = m.groups[2]?.value
    rawAuthority = m.groups[4]?.value
    rawPath = m.groups[5]?.value.orEmpty()
    rawQuery = m.groups[7]?.value
    rawFragment = m.groups[9]?.value
  }

  val userInfo: String?
    get() = rawAuthority?.takeIf { it.contains('@') }?.substringBeforeLast('@')

  val path: String get() = percentDecodePath(rawPath)
  val query: String? get() = rawQuery?.let { percentDecodePath(it) }
  val fragment: String? get() = rawFragment?.let { percentDecodePath(it) }

  val host: String?
    get() {
      val authority = rawAuthority?.substringAfterLast('@') ?: return null
      val h = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
      if (h.isEmpty()) return null
      // The JDK treats names with underscores and similar as registry-based: host is null.
      return if (h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '[' || it == ']' || it == ':' }) h else null
    }

  val port: Int
    get() {
      val authority = rawAuthority?.substringAfterLast('@') ?: return -1
      if (authority.startsWith("[")) return authority.substringAfter("]:", "").toIntOrNull() ?: -1
      return authority.substringAfter(':', "").toIntOrNull() ?: -1
    }

  val isAbsolute: Boolean get() = scheme != null

  fun resolve(reference: String): URI = resolve(URI(reference))

  fun resolve(ref: URI): URI {
    if (ref.scheme != null) return ref
    val authority: String?
    val path: String
    val query: String?
    if (ref.rawAuthority != null) {
      authority = ref.rawAuthority
      path = removeDotSegments(ref.rawPath)
      query = ref.rawQuery
    } else if (ref.rawPath.isEmpty()) {
      authority = rawAuthority
      path = rawPath
      query = ref.rawQuery ?: rawQuery
    } else {
      authority = rawAuthority
      path = if (ref.rawPath.startsWith("/")) {
        removeDotSegments(ref.rawPath)
      } else {
        removeDotSegments(merge(ref.rawPath))
      }
      query = ref.rawQuery
    }
    return URI(
      buildString {
        scheme?.let { append(it).append(':') }
        authority?.let { append("//").append(it) }
        append(path)
        query?.let { append('?').append(it) }
        ref.rawFragment?.let { append('#').append(it) }
      },
    )
  }

  private fun merge(relative: String): String =
    if (rawAuthority != null && rawPath.isEmpty()) "/$relative" else rawPath.substringBeforeLast('/', "") + "/" + relative

  override fun toString(): String = text
  override fun equals(other: Any?): Boolean = other is URI && other.text == text
  override fun hashCode(): Int = text.hashCode()

  private companion object {
    val PATTERN = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$")

    fun removeDotSegments(path: String): String {
      val output = ArrayList<String>()
      val segments = path.split('/')
      for ((i, seg) in segments.withIndex()) {
        when (seg) {
          "." -> if (i == segments.lastIndex) output += ""
          ".." -> {
            if (output.size > 1) output.removeAt(output.lastIndex)
            if (i == segments.lastIndex) output += ""
          }
          else -> output += seg
        }
      }
      return output.joinToString("/")
    }

    fun percentDecodePath(text: String): String {
      if ('%' !in text) return text
      val bytes = ArrayList<Byte>()
      var i = 0
      while (i < text.length) {
        val ch = text[i]
        if (ch == '%' && i + 3 <= text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null) {
          bytes += text.substring(i + 1, i + 3).toInt(16).toByte()
          i += 3
        } else {
          bytes += ch.toString().encodeToByteArray().toList()
          i++
        }
      }
      return bytes.toByteArray().decodeToString()
    }
  }
}
