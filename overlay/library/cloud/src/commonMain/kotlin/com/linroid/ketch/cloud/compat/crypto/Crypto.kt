package com.linroid.ketch.cloud.compat.crypto

/**
 * Pure-Kotlin MD5 / SHA-1 / SHA-256 and AES (ECB, CBC, PKCS5 padding) behind the
 * `java.security.MessageDigest` and `javax.crypto.Cipher` shapes the YunX code calls, so the
 * same code runs on Kotlin/Native without platform crypto bindings.
 */
class MessageDigest private constructor(private val algorithm: String) {
  private var buffer = ByteArray(0)

  fun update(input: ByteArray) {
    buffer += input
  }

  fun digest(): ByteArray {
    val out = when (algorithm) {
      "MD5" -> md5(buffer)
      "SHA-1" -> sha1(buffer)
      "SHA-256" -> sha256(buffer)
      else -> throw IllegalArgumentException("Unsupported digest: $algorithm")
    }
    buffer = ByteArray(0)
    return out
  }

  fun digest(input: ByteArray): ByteArray {
    update(input)
    return digest()
  }

  companion object {
    fun getInstance(algorithm: String): MessageDigest {
      val normalised = when (algorithm.uppercase()) {
        "MD5" -> "MD5"
        "SHA-1", "SHA1" -> "SHA-1"
        "SHA-256", "SHA256" -> "SHA-256"
        else -> throw IllegalArgumentException("Unsupported digest: $algorithm")
      }
      return MessageDigest(normalised)
    }
  }
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun pad(message: ByteArray, bigEndianLength: Boolean): ByteArray {
  val bitLength = message.size.toLong() * 8
  val padded = message.size + 1 + ((119 - message.size % 64) % 64 + 64) % 64 + 8
  val out = ByteArray(padded)
  message.copyInto(out)
  out[message.size] = 0x80.toByte()
  for (i in 0 until 8) {
    val shift = if (bigEndianLength) (7 - i) * 8 else i * 8
    out[padded - 8 + i] = (bitLength ushr shift).toByte()
  }
  return out
}

private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

private val MD5_SHIFTS = intArrayOf(
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val MD5_K = IntArray(64) { i ->
  // floor(2^32 * abs(sin(i + 1)))
  (kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt()
}

internal fun md5(message: ByteArray): ByteArray {
  var a0 = 0x67452301
  var b0 = 0xefcdab89L.toInt()
  var c0 = 0x98badcfeL.toInt()
  var d0 = 0x10325476
  val data = pad(message, bigEndianLength = false)
  val m = IntArray(16)
  for (chunk in data.indices step 64) {
    for (i in 0 until 16) {
      val o = chunk + i * 4
      m[i] = (data[o].toInt() and 0xFF) or
        ((data[o + 1].toInt() and 0xFF) shl 8) or
        ((data[o + 2].toInt() and 0xFF) shl 16) or
        ((data[o + 3].toInt() and 0xFF) shl 24)
    }
    var a = a0
    var b = b0
    var c = c0
    var d = d0
    for (i in 0 until 64) {
      var f: Int
      val g: Int
      when (i / 16) {
        0 -> { f = (b and c) or (b.inv() and d); g = i }
        1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
        2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
        else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
      }
      f = f + a + MD5_K[i] + m[g]
      a = d
      d = c
      c = b
      b += rotl(f, MD5_SHIFTS[i])
    }
    a0 += a
    b0 += b
    c0 += c
    d0 += d
  }
  val out = ByteArray(16)
  intArrayOf(a0, b0, c0, d0).forEachIndexed { i, v ->
    for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte()
  }
  return out
}

internal fun sha1(message: ByteArray): ByteArray {
  var h0 = 0x67452301
  var h1 = 0xEFCDAB89L.toInt()
  var h2 = 0x98BADCFEL.toInt()
  var h3 = 0x10325476
  var h4 = 0xC3D2E1F0L.toInt()
  val data = pad(message, bigEndianLength = true)
  val w = IntArray(80)
  for (chunk in data.indices step 64) {
    for (i in 0 until 16) {
      val o = chunk + i * 4
      w[i] = ((data[o].toInt() and 0xFF) shl 24) or
        ((data[o + 1].toInt() and 0xFF) shl 16) or
        ((data[o + 2].toInt() and 0xFF) shl 8) or
        (data[o + 3].toInt() and 0xFF)
    }
    for (i in 16 until 80) w[i] = rotl(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
    var a = h0
    var b = h1
    var c = h2
    var d = h3
    var e = h4
    for (i in 0 until 80) {
      val f: Int
      val k: Int
      when {
        i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
        i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
        i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDCL.toInt() }
        else -> { f = b xor c xor d; k = 0xCA62C1D6L.toInt() }
      }
      val temp = rotl(a, 5) + f + e + k + w[i]
      e = d
      d = c
      c = rotl(b, 30)
      b = a
      a = temp
    }
    h0 += a
    h1 += b
    h2 += c
    h3 += d
    h4 += e
  }
  val out = ByteArray(20)
  intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { i, v ->
    for (j in 0 until 4) out[i * 4 + j] = (v ushr (24 - 8 * j)).toByte()
  }
  return out
}

private val SHA256_K = intArrayOf(
  0x428a2f98, 0x71374491, 0xb5c0fbcfL.toInt(), 0xe9b5dba5L.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4L.toInt(), 0xab1c5ed5L.toInt(),
  0xd807aa98L.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1feL.toInt(), 0x9bdc06a7L.toInt(), 0xc19bf174L.toInt(),
  0xe49b69c1L.toInt(), 0xefbe4786L.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152L.toInt(), 0xa831c66dL.toInt(), 0xb00327c8L.toInt(), 0xbf597fc7L.toInt(), 0xc6e00bf3L.toInt(), 0xd5a79147L.toInt(), 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92eL.toInt(), 0x92722c85L.toInt(),
  0xa2bfe8a1L.toInt(), 0xa81a664bL.toInt(), 0xc24b8b70L.toInt(), 0xc76c51a3L.toInt(), 0xd192e819L.toInt(), 0xd6990624L.toInt(), 0xf40e3585L.toInt(), 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814L.toInt(), 0x8cc70208L.toInt(), 0x90befffaL.toInt(), 0xa4506cebL.toInt(), 0xbef9a3f7L.toInt(), 0xc67178f2L.toInt(),
)

internal fun sha256(message: ByteArray): ByteArray {
  val h = intArrayOf(
    0x6a09e667, 0xbb67ae85L.toInt(), 0x3c6ef372, 0xa54ff53aL.toInt(),
    0x510e527f, 0x9b05688cL.toInt(), 0x1f83d9ab, 0x5be0cd19,
  )
  val data = pad(message, bigEndianLength = true)
  val w = IntArray(64)
  for (chunk in data.indices step 64) {
    for (i in 0 until 16) {
      val o = chunk + i * 4
      w[i] = ((data[o].toInt() and 0xFF) shl 24) or
        ((data[o + 1].toInt() and 0xFF) shl 16) or
        ((data[o + 2].toInt() and 0xFF) shl 8) or
        (data[o + 3].toInt() and 0xFF)
    }
    for (i in 16 until 64) {
      val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
      val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
      w[i] = w[i - 16] + s0 + w[i - 7] + s1
    }
    var a = h[0]
    var b = h[1]
    var c = h[2]
    var d = h[3]
    var e = h[4]
    var f = h[5]
    var g = h[6]
    var hh = h[7]
    for (i in 0 until 64) {
      val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
      val ch = (e and f) xor (e.inv() and g)
      val t1 = hh + s1 + ch + SHA256_K[i] + w[i]
      val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
      val maj = (a and b) xor (a and c) xor (b and c)
      val t2 = s0 + maj
      hh = g
      g = f
      f = e
      e = d + t1
      d = c
      c = b
      b = a
      a = t1 + t2
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh
  }
  val out = ByteArray(32)
  h.forEachIndexed { i, v -> for (j in 0 until 4) out[i * 4 + j] = (v ushr (24 - 8 * j)).toByte() }
  return out
}

class SecretKeySpec(val key: ByteArray, val algorithm: String)

class IvParameterSpec(val iv: ByteArray)

/** AES with ECB or CBC and PKCS5/PKCS7 padding (or none). */
class Cipher private constructor(private val mode: String, private val padding: Boolean) {
  private var encrypting = true
  private var key: ByteArray = ByteArray(0)
  private var iv: ByteArray = ByteArray(16)

  fun init(opmode: Int, key: SecretKeySpec) {
    encrypting = opmode == ENCRYPT_MODE
    this.key = key.key
    require(this.key.size in intArrayOf(16, 24, 32)) { "Invalid AES key length: ${this.key.size}" }
  }

  fun init(opmode: Int, key: SecretKeySpec, iv: IvParameterSpec) {
    init(opmode, key)
    require(iv.iv.size == 16) { "Wrong IV length: must be 16 bytes long" }
    this.iv = iv.iv
  }

  fun doFinal(input: ByteArray): ByteArray {
    val aes = Aes(key)
    return if (encrypting) encrypt(aes, input) else decrypt(aes, input)
  }

  private fun encrypt(aes: Aes, input: ByteArray): ByteArray {
    val data = if (padding) {
      val n = 16 - input.size % 16
      input + ByteArray(n) { n.toByte() }
    } else {
      require(input.size % 16 == 0) { "Input length not multiple of 16 bytes" }
      input
    }
    val out = ByteArray(data.size)
    var prev = iv
    for (offset in data.indices step 16) {
      val block = data.copyOfRange(offset, offset + 16)
      if (mode == "CBC") for (i in 0 until 16) block[i] = (block[i].toInt() xor prev[i].toInt()).toByte()
      val enc = aes.encryptBlock(block)
      enc.copyInto(out, offset)
      prev = enc
    }
    return out
  }

  private fun decrypt(aes: Aes, input: ByteArray): ByteArray {
    require(input.size % 16 == 0) { "Input length must be multiple of 16 when decrypting with padded cipher" }
    val out = ByteArray(input.size)
    var prev = iv
    for (offset in input.indices step 16) {
      val block = input.copyOfRange(offset, offset + 16)
      val dec = aes.decryptBlock(block)
      if (mode == "CBC") for (i in 0 until 16) dec[i] = (dec[i].toInt() xor prev[i].toInt()).toByte()
      dec.copyInto(out, offset)
      prev = block
    }
    if (!padding) return out
    val n = out.lastOrNull()?.toInt()?.and(0xFF) ?: 0
    require(n in 1..16 && n <= out.size) { "Given final block not properly padded" }
    return out.copyOf(out.size - n)
  }

  companion object {
    const val ENCRYPT_MODE = 1
    const val DECRYPT_MODE = 2

    fun getInstance(transformation: String): Cipher {
      val parts = transformation.uppercase().split('/')
      require(parts[0] == "AES") { "Unsupported cipher: $transformation" }
      val mode = parts.getOrElse(1) { "ECB" }
      require(mode == "ECB" || mode == "CBC") { "Unsupported mode: $transformation" }
      val padding = parts.getOrElse(2) { "PKCS5PADDING" }
      return Cipher(mode, padding == "PKCS5PADDING" || padding == "PKCS7PADDING")
    }
  }
}

private class Aes(key: ByteArray) {
  private val rounds = key.size / 4 + 6
  private val roundKeys: Array<IntArray>

  init {
    val words = IntArray(4 * (rounds + 1))
    val nk = key.size / 4
    for (i in 0 until nk) {
      words[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
        ((key[4 * i + 2].toInt() and 0xFF) shl 8) or (key[4 * i + 3].toInt() and 0xFF)
    }
    var rcon = 1
    for (i in nk until words.size) {
      var t = words[i - 1]
      if (i % nk == 0) {
        t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
        rcon = xtime(rcon)
      } else if (nk > 6 && i % nk == 4) {
        t = subWord(t)
      }
      words[i] = words[i - nk] xor t
    }
    roundKeys = Array(rounds + 1) { r -> IntArray(4) { c -> words[r * 4 + c] } }
  }

  fun encryptBlock(input: ByteArray): ByteArray {
    val s = stateOf(input)
    addRoundKey(s, 0)
    for (r in 1 until rounds) {
      subBytes(s, SBOX)
      shiftRows(s, inverse = false)
      mixColumns(s, inverse = false)
      addRoundKey(s, r)
    }
    subBytes(s, SBOX)
    shiftRows(s, inverse = false)
    addRoundKey(s, rounds)
    return s
  }

  fun decryptBlock(input: ByteArray): ByteArray {
    val s = stateOf(input)
    addRoundKey(s, rounds)
    for (r in rounds - 1 downTo 1) {
      shiftRows(s, inverse = true)
      subBytes(s, INV_SBOX)
      addRoundKey(s, r)
      mixColumns(s, inverse = true)
    }
    shiftRows(s, inverse = true)
    subBytes(s, INV_SBOX)
    addRoundKey(s, 0)
    return s
  }

  // State is kept in column-major order, the same as the input byte order.
  private fun stateOf(input: ByteArray): ByteArray = input.copyOf()

  private fun addRoundKey(s: ByteArray, round: Int) {
    val rk = roundKeys[round]
    for (c in 0 until 4) for (r in 0 until 4) {
      s[c * 4 + r] = (s[c * 4 + r].toInt() xor (rk[c] ushr (24 - 8 * r))).toByte()
    }
  }

  private fun subBytes(s: ByteArray, box: IntArray) {
    for (i in 0 until 16) s[i] = box[s[i].toInt() and 0xFF].toByte()
  }

  private fun shiftRows(s: ByteArray, inverse: Boolean) {
    val t = s.copyOf()
    for (c in 0 until 4) for (r in 0 until 4) {
      val from = if (inverse) (c - r + 4) % 4 else (c + r) % 4
      s[c * 4 + r] = t[from * 4 + r]
    }
  }

  private fun mixColumns(s: ByteArray, inverse: Boolean) {
    for (c in 0 until 4) {
      val a = IntArray(4) { s[c * 4 + it].toInt() and 0xFF }
      val m = if (inverse) intArrayOf(14, 11, 13, 9) else intArrayOf(2, 3, 1, 1)
      for (r in 0 until 4) {
        var v = 0
        for (k in 0 until 4) v = v xor gmul(a[k], m[(k - r + 4) % 4])
        s[c * 4 + r] = v.toByte()
      }
    }
  }

  private companion object {
    fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x11b else 0)) and 0xFF

    fun gmul(a: Int, b: Int): Int {
      var result = 0
      var x = a
      var y = b
      while (y > 0) {
        if (y and 1 != 0) result = result xor x
        x = xtime(x)
        y = y shr 1
      }
      return result
    }

    val SBOX: IntArray = IntArray(256).also { box ->
      // Multiplicative inverse in GF(2^8) followed by the affine transform.
      var p = 1
      var q = 1
      do {
        p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
        q = q xor (q shl 1)
        q = q xor (q shl 2)
        q = q xor (q shl 4)
        q = q and 0xFF
        if (q and 0x80 != 0) q = q xor 0x09
        val x = q xor ((q shl 1) or (q ushr 7)) xor ((q shl 2) or (q ushr 6)) xor
          ((q shl 3) or (q ushr 5)) xor ((q shl 4) or (q ushr 4))
        box[p] = (x xor 0x63) and 0xFF
      } while (p != 1)
      box[0] = 0x63
    }

    val INV_SBOX: IntArray = IntArray(256).also { inv -> for (i in 0 until 256) inv[SBOX[i]] = i }

    fun subWord(w: Int): Int =
      (SBOX[(w ushr 24) and 0xFF] shl 24) or (SBOX[(w ushr 16) and 0xFF] shl 16) or
        (SBOX[(w ushr 8) and 0xFF] shl 8) or SBOX[w and 0xFF]
  }
}
