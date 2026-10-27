package com.linroid.ketch.cloud.compat.util

/** `java.io.ByteArrayOutputStream`. */
class ByteArrayOutputStream(initialSize: Int = 32) {
  private var buffer = ByteArray(maxOf(initialSize, 16))
  private var count = 0

  fun size(): Int = count

  fun reset() {
    count = 0
  }

  fun write(byte: Int) {
    ensure(1)
    buffer[count++] = byte.toByte()
  }

  fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

  fun write(bytes: ByteArray, offset: Int, length: Int) {
    ensure(length)
    bytes.copyInto(buffer, count, offset, offset + length)
    count += length
  }

  fun toByteArray(): ByteArray = buffer.copyOf(count)

  fun close() {}

  private fun ensure(extra: Int) {
    if (count + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, count + extra))
  }
}

/** `System.arraycopy`. */
fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
  src.copyInto(dest, destPos, srcPos, srcPos + length)
}

/** `System.arraycopy` for `IntArray`s. */
fun arraycopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
  src.copyInto(dest, destPos, srcPos, srcPos + length)
}
