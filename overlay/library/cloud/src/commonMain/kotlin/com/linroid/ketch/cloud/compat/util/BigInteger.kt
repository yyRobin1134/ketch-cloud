package com.linroid.ketch.cloud.compat.util

/**
 * A minimal non-negative `java.math.BigInteger`: parsing, `valueOf`, `modPow` (odd modulus,
 * Montgomery multiplication) and `toByteArray`. It exists for the 115 drive's RSA step.
 */
class BigInteger private constructor(private val limbs: IntArray) {

  /** Builds from a sign (only 1 and 0 are supported) and big-endian magnitude bytes. */
  constructor(signum: Int, magnitude: ByteArray) : this(fromBytes(magnitude)) {
    require(signum == 1 || signum == 0) { "Only non-negative values are supported" }
  }

  constructor(text: String, radix: Int) : this(fromText(text, radix))

  private val size: Int get() = limbs.indexOfLast { it != 0 } + 1

  fun bitLength(): Int {
    val n = size
    return if (n == 0) 0 else (n - 1) * 32 + (32 - limbs[n - 1].countLeadingZeroBits())
  }

  private fun bit(index: Int): Boolean =
    index / 32 < limbs.size && (limbs[index / 32] ushr (index % 32)) and 1 != 0

  /** Two's-complement big-endian bytes, minimal length, like the JDK method. */
  fun toByteArray(): ByteArray {
    val bytes = (bitLength() + 8) / 8
    return ByteArray(maxOf(bytes, 1)) { i ->
      val byteIndex = bytes - 1 - i
      ((limbs.getOrElse(byteIndex / 4) { 0 } ushr (8 * (byteIndex % 4))) and 0xFF).toByte()
    }
  }

  override fun toString(): String = toByteArray().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.trimStart('0').ifEmpty { "0" }
  override fun equals(other: Any?): Boolean = other is BigInteger && other.size == size && (0 until size).all { limbs[it] == other.limbs[it] }
  override fun hashCode(): Int = limbs.take(size).hashCode()

  fun modPow(exponent: BigInteger, modulus: BigInteger): BigInteger {
    require(modulus.size > 0 && modulus.limbs[0] and 1 != 0) { "Only odd moduli are supported" }
    val n = modulus.size
    val m = modulus.limbs.copyOf(n)
    // -m^-1 mod 2^32 by Newton iteration.
    var inv = 1
    repeat(5) { inv *= 2 - m[0] * inv }
    val n0 = -inv

    fun montMul(a: IntArray, b: IntArray): IntArray {
      val t = IntArray(n + 2)
      for (i in 0 until n) {
        var carry = 0L
        val ai = a[i].toLong() and 0xFFFFFFFFL
        for (j in 0 until n) {
          val cur = (t[j].toLong() and 0xFFFFFFFFL) + ai * (b[j].toLong() and 0xFFFFFFFFL) + carry
          t[j] = cur.toInt()
          carry = cur ushr 32
        }
        val top = (t[n].toLong() and 0xFFFFFFFFL) + carry
        t[n] = top.toInt()
        t[n + 1] = (top ushr 32).toInt()
        val q = (t[0] * n0).toLong() and 0xFFFFFFFFL
        carry = ((t[0].toLong() and 0xFFFFFFFFL) + q * (m[0].toLong() and 0xFFFFFFFFL)) ushr 32
        for (j in 1 until n) {
          val cur = (t[j].toLong() and 0xFFFFFFFFL) + q * (m[j].toLong() and 0xFFFFFFFFL) + carry
          t[j - 1] = cur.toInt()
          carry = cur ushr 32
        }
        val last = (t[n].toLong() and 0xFFFFFFFFL) + carry
        t[n - 1] = last.toInt()
        t[n] = t[n + 1] + (last ushr 32).toInt()
      }
      var result = t.copyOf(n)
      val overflow = t[n] != 0
      if (overflow || compare(result, m) >= 0) result = subtract(result, m)
      return result
    }

    // R^2 mod m, where R = 2^(32n): double 1 a total of 64n times, reducing each step.
    var r2 = IntArray(n).also { it[0] = 1 }
    repeat(64 * n) {
      val carry = shiftLeftOne(r2)
      if (carry || compare(r2, m) >= 0) r2 = subtract(r2, m)
    }
    val base = limbs.copyOf(n)
    var acc = montMul(IntArray(n).also { it[0] = 1 }, r2) // 1 in Montgomery form
    val baseMont = montMul(base, r2)
    for (i in exponent.bitLength() - 1 downTo 0) {
      acc = montMul(acc, acc)
      if (exponent.bit(i)) acc = montMul(acc, baseMont)
    }
    return BigInteger(montMul(acc, IntArray(n).also { it[0] = 1 }))
  }

  companion object {
    fun valueOf(value: Long): BigInteger {
      require(value >= 0) { "Only non-negative values are supported" }
      return BigInteger(intArrayOf(value.toInt(), (value ushr 32).toInt()))
    }

    private fun fromBytes(bytes: ByteArray): IntArray {
      val out = IntArray((bytes.size + 3) / 4 + 1)
      for (i in bytes.indices) {
        val byteIndex = bytes.size - 1 - i
        out[byteIndex / 4] = out[byteIndex / 4] or ((bytes[i].toInt() and 0xFF) shl (8 * (byteIndex % 4)))
      }
      return out
    }

    private fun fromText(text: String, radix: Int): IntArray {
      require(radix == 16) { "Only radix 16 is supported" }
      val clean = text.trim().removePrefix("0x")
      val padded = if (clean.length % 2 == 0) clean else "0$clean"
      return fromBytes(ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }

    private fun compare(a: IntArray, b: IntArray): Int {
      for (i in a.indices.reversed()) {
        val x = a[i].toLong() and 0xFFFFFFFFL
        val y = b[i].toLong() and 0xFFFFFFFFL
        if (x != y) return if (x < y) -1 else 1
      }
      return 0
    }

    private fun subtract(a: IntArray, b: IntArray): IntArray {
      val out = IntArray(a.size)
      var borrow = 0L
      for (i in a.indices) {
        val d = (a[i].toLong() and 0xFFFFFFFFL) - (b[i].toLong() and 0xFFFFFFFFL) - borrow
        out[i] = d.toInt()
        borrow = if (d < 0) 1 else 0
      }
      return out
    }

    /** Shifts left by one bit in place; returns the bit shifted out of the top limb. */
    private fun shiftLeftOne(a: IntArray): Boolean {
      var carry = 0
      for (i in a.indices) {
        val next = a[i] ushr 31
        a[i] = (a[i] shl 1) or carry
        carry = next
      }
      return carry != 0
    }
  }
}
