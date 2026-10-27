package com.linroid.ketch.cloud.compat.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** `java.net.URLEncoder` / `URLDecoder` for the UTF-8 form encoding the YunX code uses. */
object URLEncoder {
  @Suppress("UNUSED_PARAMETER")
  fun encode(text: String, charset: String = "UTF-8"): String {
    val out = StringBuilder()
    for (byte in text.encodeToByteArray()) {
      val c = byte.toInt() and 0xFF
      val ch = c.toChar()
      when {
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> out.append(ch)
        ch == ' ' -> out.append('+')
        else -> out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
      }
    }
    return out.toString()
  }
}

object URLDecoder {
  @Suppress("UNUSED_PARAMETER")
  fun decode(text: String, charset: String = "UTF-8"): String {
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < text.length) {
      val ch = text[i]
      if (ch == '+') {
        bytes += ' '.code.toByte()
        i++
      } else if (ch == '%' && i + 2 < text.length + 1 && i + 3 <= text.length &&
        text.substring(i + 1, i + 3).toIntOrNull(16) != null
      ) {
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

/** `android.util.Base64` with its flag constants. */
@OptIn(ExperimentalEncodingApi::class)
object AndroidBase64 {
  const val DEFAULT = 0
  const val NO_PADDING = 1
  const val NO_WRAP = 2
  const val CRLF = 4
  const val URL_SAFE = 8

  fun encodeToString(input: ByteArray, flags: Int): String {
    var codec = if (flags and URL_SAFE != 0) Base64.UrlSafe else Base64.Default
    if (flags and NO_PADDING != 0) codec = codec.withPadding(Base64.PaddingOption.ABSENT)
    val encoded = codec.encode(input)
    return if (flags and NO_WRAP != 0) {
      encoded
    } else {
      val eol = if (flags and CRLF != 0) "\r\n" else "\n"
      encoded.chunked(76).joinToString(eol, postfix = eol)
    }
  }

  fun decode(input: String, flags: Int): ByteArray {
    val codec = (if (flags and URL_SAFE != 0) Base64.UrlSafe else Base64.Default)
      .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    return codec.decode(input.filterNot { it == '\n' || it == '\r' || it == ' ' })
  }
}

/** Replaces `android.util.Log`; the parsers log a handful of diagnostics. */
object Log {
  var sink: ((level: String, tag: String, message: String) -> Unit)? = null

  fun d(tag: String, message: String): Int = emit("D", tag, message)
  fun i(tag: String, message: String): Int = emit("I", tag, message)
  fun w(tag: String, message: String): Int = emit("W", tag, message)
  fun e(tag: String, message: String): Int = emit("E", tag, message)
  fun e(tag: String, message: String, error: Throwable?): Int = emit("E", tag, "$message: $error")
  fun w(tag: String, message: String, error: Throwable?): Int = emit("W", tag, "$message: $error")

  private fun emit(level: String, tag: String, message: String): Int {
    sink?.invoke(level, tag, message)
    return 0
  }
}
