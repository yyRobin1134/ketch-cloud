package com.linroid.ketch.cloud.compat.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** `java.util.Locale`; only the names are kept because month and day names are English. */
class Locale private constructor(val tag: String) {
  companion object {
    val US = Locale("en-US")
    val ENGLISH = Locale("en")
    val ROOT = Locale("")
    val CHINA = Locale("zh-CN")
    fun getDefault(): Locale = US
  }
}

/** `java.util.Date`. */
class Date(val time: Long = currentTimeMillis()) {
  override fun toString(): String = Instant.fromEpochMilliseconds(time).toString()
}

/** `java.util.TimeZone`, backed by kotlinx-datetime. */
class TimeZone private constructor(internal val zone: kotlinx.datetime.TimeZone) {
  val id: String get() = zone.id

  companion object {
    fun getTimeZone(id: String): TimeZone =
      TimeZone(if (id == "UTC" || id == "GMT") kotlinx.datetime.TimeZone.UTC else kotlinx.datetime.TimeZone.of(id))

    fun getDefault(): TimeZone = TimeZone(kotlinx.datetime.TimeZone.currentSystemDefault())
  }
}

/** The few `java.util.Calendar` fields the YunX code reads. */
class Calendar private constructor(private val zone: TimeZone) {
  var timeInMillis: Long = currentTimeMillis()

  fun get(field: Int): Int {
    val t = Instant.fromEpochMilliseconds(timeInMillis).toLocalDateTime(zone.zone)
    return when (field) {
      YEAR -> t.year
      MONTH -> t.month.ordinal
      DAY_OF_MONTH -> t.day
      HOUR_OF_DAY -> t.hour
      MINUTE -> t.minute
      SECOND -> t.second
      else -> throw IllegalArgumentException("Unsupported calendar field: $field")
    }
  }

  companion object {
    const val YEAR = 1
    const val MONTH = 2
    const val DAY_OF_MONTH = 5
    const val HOUR_OF_DAY = 11
    const val MINUTE = 12
    const val SECOND = 13

    fun getInstance(zone: TimeZone = TimeZone.getDefault()): Calendar = Calendar(zone)
  }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * `java.text.SimpleDateFormat` for the patterns the YunX parsers use: `y M d H m s S E`,
 * the zone letters `Z X`, and quoted literals. Parsing is lenient about digit counts and
 * returns null (rather than throwing `ParseException`) when the text does not match.
 */
class SimpleDateFormat(private val pattern: String, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault()) {
  var timeZone: TimeZone = TimeZone.getDefault()

  private sealed interface Token {
    data class Literal(val text: String) : Token
    data class Field(val letter: Char, val count: Int) : Token
  }

  private val tokens: List<Token> = buildList {
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      if (c == '\'') {
        val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
        add(Token.Literal(if (end == i + 1) "'" else pattern.substring(i + 1, end)))
        i = end + 1
      } else if (c.isLetter()) {
        var j = i
        while (j < pattern.length && pattern[j] == c) j++
        add(Token.Field(c, j - i))
        i = j
      } else {
        add(Token.Literal(c.toString()))
        i++
      }
    }
  }

  fun format(date: Date): String {
    val instant = Instant.fromEpochMilliseconds(date.time)
    val t = instant.toLocalDateTime(timeZone.zone)
    val offsetSeconds = instant.offsetIn(timeZone.zone).totalSeconds
    return buildString {
      for (token in tokens) when (token) {
        is Token.Literal -> append(token.text)
        is Token.Field -> append(
          when (token.letter) {
            'y' -> if (token.count == 2) (t.year % 100).toString().padStart(2, '0') else t.year.toString().padStart(token.count, '0')
            'M' -> if (token.count >= 3) MONTHS[t.month.ordinal] else (t.month.ordinal + 1).toString().padStart(token.count, '0')
            'd' -> t.day.toString().padStart(token.count, '0')
            'H' -> t.hour.toString().padStart(token.count, '0')
            'm' -> t.minute.toString().padStart(token.count, '0')
            's' -> t.second.toString().padStart(token.count, '0')
            'S' -> (date.time.mod(1000L)).toString().padStart(3, '0').take(token.count)
            'E' -> DAYS[t.dayOfWeek.ordinal]
            'Z' -> offsetText(offsetSeconds, colon = false, zulu = false)
            'X' -> offsetText(offsetSeconds, colon = token.count >= 3, zulu = true)
            else -> throw IllegalArgumentException("Unsupported pattern letter: ${token.letter}")
          },
        )
      }
    }
  }

  fun parse(text: String): Date? {
    var pos = 0
    var year = 1970
    var month = 1
    var day = 1
    var hour = 0
    var minute = 0
    var second = 0
    var millis = 0
    var offset: UtcOffset? = null

    fun digits(max: Int): Int? {
      val start = pos
      while (pos < text.length && pos - start < max && text[pos].isDigit()) pos++
      return if (pos == start) null else text.substring(start, pos).toInt()
    }

    for ((index, token) in tokens.withIndex()) {
      when (token) {
        is Token.Literal -> {
          if (!text.startsWith(token.text, pos)) return null
          pos += token.text.length
        }
        is Token.Field -> {
          // Adjacent numeric fields (yyyyMMdd) need their exact width; otherwise be lenient.
          val nextIsField = tokens.getOrNull(index + 1) is Token.Field
          when (token.letter) {
            'y' -> year = digits(if (token.count == 2) 2 else 4) ?: return null
            'M' -> if (token.count >= 3) {
              val name = MONTHS.indexOfFirst { text.startsWith(it, pos, ignoreCase = true) }
              if (name < 0) return null
              month = name + 1
              pos += 3
            } else {
              month = digits(if (nextIsField) token.count else 2) ?: return null
            }
            'd' -> day = digits(if (nextIsField) token.count else 2) ?: return null
            'H' -> hour = digits(if (nextIsField) token.count else 2) ?: return null
            'm' -> minute = digits(if (nextIsField) token.count else 2) ?: return null
            's' -> second = digits(if (nextIsField) token.count else 2) ?: return null
            'S' -> millis = digits(3) ?: return null
            'E' -> {
              if (DAYS.none { text.startsWith(it, pos, ignoreCase = true) }) return null
              pos += 3
            }
            'Z', 'X' -> {
              if (token.letter == 'X' && text.startsWith("Z", pos)) {
                offset = UtcOffset.ZERO
                pos++
              } else {
                val sign = when (text.getOrNull(pos)) { '+' -> 1; '-' -> -1; else -> return null }
                pos++
                val hh = digits(2) ?: return null
                if (text.getOrNull(pos) == ':') pos++
                val mm = digits(2) ?: 0
                offset = UtcOffset(seconds = sign * (hh * 3600 + mm * 60))
              }
            }
            else -> throw IllegalArgumentException("Unsupported pattern letter: ${token.letter}")
          }
        }
      }
    }
    return try {
      val local = LocalDateTime(year, month, day, hour, minute, second)
      val instant = if (offset != null) local.toInstant(offset) else local.toInstant(timeZone.zone)
      Date(instant.toEpochMilliseconds() + millis)
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun offsetText(seconds: Int, colon: Boolean, zulu: Boolean): String {
    if (zulu && seconds == 0) return "Z"
    val sign = if (seconds < 0) "-" else "+"
    val abs = kotlin.math.abs(seconds)
    val hh = (abs / 3600).toString().padStart(2, '0')
    val mm = (abs % 3600 / 60).toString().padStart(2, '0')
    return sign + hh + (if (colon) ":" else "") + mm
  }
}
