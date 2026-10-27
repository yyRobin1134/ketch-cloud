package com.linroid.ketch.cloud.compat.util

/**
 * `String.format` for common code, covering the conversions the YunX sources use:
 * `%d %s %x %X %f %c %b %%` with the `-`, `0`, `+`, space flags, width and precision.
 */
fun String.format(vararg args: Any?): String {
  val out = StringBuilder()
  var argIndex = 0
  var i = 0
  while (i < length) {
    val ch = this[i]
    if (ch != '%') {
      out.append(ch)
      i++
      continue
    }
    i++
    require(i < length) { "Incomplete format specifier" }
    var leftAlign = false
    var zeroPad = false
    var plus = false
    var space = false
    var comma = false
    while (i < length && this[i] in "-0+ ,") {
      when (this[i]) {
        '-' -> leftAlign = true
        '0' -> zeroPad = true
        '+' -> plus = true
        ' ' -> space = true
        ',' -> comma = true
      }
      i++
    }
    var width = 0
    while (i < length && this[i].isDigit()) width = width * 10 + (this[i++] - '0')
    var precision = -1
    if (i < length && this[i] == '.') {
      i++
      precision = 0
      while (i < length && this[i].isDigit()) precision = precision * 10 + (this[i++] - '0')
    }
    require(i < length) { "Incomplete format specifier" }
    val conv = this[i++]
    if (conv == '%') {
      out.append('%')
      continue
    }
    if (conv == 'n') {
      out.append('\n')
      continue
    }
    val arg = args.getOrNull(argIndex++)
    var body = when (conv) {
      'd' -> {
        val n = (arg as? Number)?.toLong() ?: throw IllegalArgumentException("%d needs a number: $arg")
        val digits = if (comma) groupThousands(kotlin.math.abs(n).toString()) else kotlin.math.abs(n).toString()
        (if (n < 0) "-" else if (plus) "+" else if (space) " " else "") + digits
      }
      'x', 'X' -> {
        val n = (arg as? Number) ?: throw IllegalArgumentException("%x needs a number: $arg")
        val hex = when (n) {
          is Byte -> (n.toInt() and 0xFF).toString(16)
          is Short -> (n.toInt() and 0xFFFF).toString(16)
          is Int -> n.toUInt().toString(16)
          else -> n.toLong().toULong().toString(16)
        }
        if (conv == 'X') hex.uppercase() else hex
      }
      'f' -> {
        val d = (arg as? Number)?.toDouble() ?: throw IllegalArgumentException("%f needs a number: $arg")
        val text = fixed(kotlin.math.abs(d), if (precision < 0) 6 else precision)
        val signed = (if (d < 0 || (d == 0.0 && 1.0 / d < 0)) "-" else if (plus) "+" else if (space) " " else "") +
          (if (comma) groupThousands(text.substringBefore('.')) + text.substring(text.indexOf('.').let { if (it < 0) text.length else it }) else text)
        signed
      }
      's', 'S' -> {
        val s = arg.toString()
        val cut = if (precision >= 0) s.take(precision) else s
        if (conv == 'S') cut.uppercase() else cut
      }
      'c' -> when (arg) {
        is Char -> arg.toString()
        is Number -> arg.toInt().toChar().toString()
        else -> arg.toString()
      }
      'b', 'B' -> (arg != null && arg != false).toString()
      else -> throw IllegalArgumentException("Unsupported conversion: %$conv")
    }
    if (body.length < width) {
      body = when {
        leftAlign -> body.padEnd(width)
        zeroPad && conv != 's' && conv != 'c' -> {
          val sign = if (body.startsWith("-") || body.startsWith("+")) body.substring(0, 1) else ""
          sign + body.substring(sign.length).padStart(width - sign.length, '0')
        }
        else -> body.padStart(width)
      }
    }
    out.append(body)
  }
  return out.toString()
}

private fun groupThousands(digits: String): String =
  digits.reversed().chunked(3).joinToString(",").reversed()

/** Rounds half-up to [places] decimals and prints without exponent notation. */
private fun fixed(value: Double, places: Int): String {
  if (value.isNaN()) return "NaN"
  if (value.isInfinite()) return "Infinity"
  var scale = 1.0
  repeat(places) { scale *= 10.0 }
  val scaled = kotlin.math.round(value * scale)
  val digits = scaled.toLong().toString().padStart(places + 1, '0')
  if (places == 0) return digits
  return digits.substring(0, digits.length - places) + "." + digits.substring(digits.length - places)
}

/** `String.format(pattern, args)`, the static form. */
fun String.Companion.format(format: String, vararg args: Any?): String = format.format(*args)

/** `String.toByteArray()`: UTF-8 bytes, as on the JVM. */
fun String.toByteArray(): ByteArray = encodeToByteArray()
