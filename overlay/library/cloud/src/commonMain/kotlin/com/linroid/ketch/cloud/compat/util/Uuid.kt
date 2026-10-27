package com.linroid.ketch.cloud.compat.util

import kotlin.random.Random

/** `java.util.UUID`, only the random (version 4) generator and its string form. */
class UUID private constructor(private val text: String) {
  override fun toString(): String = text
  override fun equals(other: Any?): Boolean = other is UUID && other.text == text
  override fun hashCode(): Int = text.hashCode()

  companion object {
    fun randomUUID(): UUID {
      val bytes = Random.Default.nextBytes(16)
      bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
      bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
      val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
      return UUID(
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
          "${hex.substring(16, 20)}-${hex.substring(20)}",
      )
    }

    fun fromString(text: String): UUID = UUID(text.lowercase())
  }
}
