package com.linroid.ketch.cloud.compat.util

/** `java.util.zip.CRC32`. */
class CRC32 {
  private var crc = 0xFFFFFFFFL.toInt()

  fun update(bytes: ByteArray) {
    for (b in bytes) crc = TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
  }

  val value: Long get() = (crc.inv()).toLong() and 0xFFFFFFFFL

  fun reset() {
    crc = 0xFFFFFFFFL.toInt()
  }

  private companion object {
    val TABLE = IntArray(256) { n ->
      var c = n
      repeat(8) { c = if (c and 1 != 0) 0xEDB88320L.toInt() xor (c ushr 1) else c ushr 1 }
      c
    }
  }
}

/** Decompresses a gzip (RFC 1952) member; raises [IllegalArgumentException] on corrupt data. */
fun gunzip(data: ByteArray): ByteArray {
  require(data.size >= 18 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) { "Not in GZIP format" }
  val flags = data[3].toInt()
  var pos = 10
  if (flags and 4 != 0) pos += 2 + ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
  if (flags and 8 != 0) { while (data[pos++].toInt() != 0) Unit }
  if (flags and 16 != 0) { while (data[pos++].toInt() != 0) Unit }
  if (flags and 2 != 0) pos += 2
  return inflate(data, pos)
}

/** Decompresses a raw DEFLATE (RFC 1951) stream that starts at [start]. */
fun inflate(data: ByteArray, start: Int = 0): ByteArray = Inflater(data, start).run()

private class Huffman(lengths: IntArray) {
  val counts = IntArray(16)
  val symbols = IntArray(lengths.size)

  init {
    for (l in lengths) counts[l]++
    counts[0] = 0
    val offsets = IntArray(16)
    for (i in 1 until 16) offsets[i] = offsets[i - 1] + counts[i - 1]
    for (sym in lengths.indices) if (lengths[sym] != 0) symbols[offsets[lengths[sym]]++] = sym
  }
}

private class Inflater(private val input: ByteArray, private var pos: Int) {
  private var bitBuf = 0
  private var bitCount = 0
  private var out = ByteArray(maxOf(1024, input.size * 3))
  private var outLen = 0

  fun run(): ByteArray {
    do {
      val last = bits(1)
      when (bits(2)) {
        0 -> stored()
        1 -> codes(FIXED_LIT, FIXED_DIST)
        2 -> dynamic()
        else -> throw IllegalArgumentException("Invalid block type")
      }
    } while (last == 0)
    return out.copyOf(outLen)
  }

  private fun bits(need: Int): Int {
    var value = bitBuf
    while (bitCount < need) {
      require(pos < input.size) { "Unexpected end of GZIP input" }
      value = value or ((input[pos++].toInt() and 0xFF) shl bitCount)
      bitCount += 8
    }
    bitBuf = value ushr need
    bitCount -= need
    return value and ((1 shl need) - 1)
  }

  private fun put(b: Int) {
    if (outLen == out.size) out = out.copyOf(out.size * 2)
    out[outLen++] = b.toByte()
  }

  private fun stored() {
    bitBuf = 0
    bitCount = 0
    require(pos + 4 <= input.size) { "Unexpected end of GZIP input" }
    val len = (input[pos].toInt() and 0xFF) or ((input[pos + 1].toInt() and 0xFF) shl 8)
    pos += 4
    require(pos + len <= input.size) { "Unexpected end of GZIP input" }
    repeat(len) { put(input[pos++].toInt()) }
  }

  private fun decode(h: Huffman): Int {
    var code = 0
    var first = 0
    var index = 0
    for (len in 1..15) {
      code = code or bits(1)
      val count = h.counts[len]
      if (code - count < first) return h.symbols[index + (code - first)]
      index += count
      first += count
      first = first shl 1
      code = code shl 1
    }
    throw IllegalArgumentException("Invalid Huffman code")
  }

  private fun codes(lit: Huffman, dist: Huffman) {
    while (true) {
      var sym = decode(lit)
      if (sym < 256) {
        put(sym)
      } else if (sym == 256) {
        return
      } else {
        sym -= 257
        require(sym < 29) { "Invalid length symbol" }
        val len = LEN_BASE[sym] + bits(LEN_EXTRA[sym])
        val dsym = decode(dist)
        require(dsym < 30) { "Invalid distance symbol" }
        val distance = DIST_BASE[dsym] + bits(DIST_EXTRA[dsym])
        require(distance <= outLen) { "Distance too far back" }
        repeat(len) { put(out[outLen - distance].toInt()) }
      }
    }
  }

  private fun dynamic() {
    val nlen = bits(5) + 257
    val ndist = bits(5) + 1
    val ncode = bits(4) + 4
    val lengths = IntArray(320)
    for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
    val lencode = Huffman(lengths.copyOf(19))
    var index = 0
    val all = IntArray(nlen + ndist)
    while (index < nlen + ndist) {
      var sym = decode(lencode)
      if (sym < 16) {
        all[index++] = sym
      } else {
        var len = 0
        val repeatCount: Int
        when (sym) {
          16 -> { require(index > 0) { "No previous length" }; len = all[index - 1]; repeatCount = 3 + bits(2) }
          17 -> repeatCount = 3 + bits(3)
          else -> repeatCount = 11 + bits(7)
        }
        require(index + repeatCount <= nlen + ndist) { "Too many lengths" }
        repeat(repeatCount) { all[index++] = len }
      }
    }
    codes(Huffman(all.copyOfRange(0, nlen)), Huffman(all.copyOfRange(nlen, nlen + ndist)))
  }

  private companion object {
    val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    val FIXED_LIT = Huffman(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 })
    val FIXED_DIST = Huffman(IntArray(30) { 5 })
  }
}
