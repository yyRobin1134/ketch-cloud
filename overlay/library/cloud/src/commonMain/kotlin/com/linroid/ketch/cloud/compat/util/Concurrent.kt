package com.linroid.ketch.cloud.compat.util

import kotlin.random.Random

/** `synchronized(lock) { }` for common code; the YunX sources are rewritten to call this. */
expect fun <T> syncLock(lock: Any, block: () -> T): T

/** `System.currentTimeMillis()`. */
expect fun currentTimeMillis(): Long

/** `System.nanoTime()`. */
expect fun nanoTime(): Long

/** `Thread.sleep`, for the few blocking retry loops. */
expect fun sleepBlocking(millis: Long)

/** A map safe for use from several threads; every operation takes one shared lock. */
class ConcurrentHashMap<K, V> : MutableMap<K, V> {
  private val lock = Any()
  private val inner = LinkedHashMap<K, V>()

  override val size: Int get() = syncLock(lock) { inner.size }
  override fun isEmpty(): Boolean = syncLock(lock) { inner.isEmpty() }
  override fun containsKey(key: K): Boolean = syncLock(lock) { inner.containsKey(key) }
  override fun containsValue(value: V): Boolean = syncLock(lock) { inner.containsValue(value) }
  override fun get(key: K): V? = syncLock(lock) { inner[key] }
  override fun put(key: K, value: V): V? = syncLock(lock) { inner.put(key, value) }
  override fun remove(key: K): V? = syncLock(lock) { inner.remove(key) }
  override fun putAll(from: Map<out K, V>) = syncLock(lock) { inner.putAll(from) }
  override fun clear() = syncLock(lock) { inner.clear() }

  fun remove(key: K, value: V): Boolean = syncLock(lock) {
    if (inner[key] == value && inner.containsKey(key)) { inner.remove(key); true } else false
  }

  fun putIfAbsent(key: K, value: V): V? = syncLock(lock) {
    inner[key] ?: run { inner[key] = value; null }
  }

  // Views are snapshots: they are safe to iterate but do not write through.
  override val keys: MutableSet<K> get() = syncLock(lock) { LinkedHashSet(inner.keys) }
  override val values: MutableCollection<V> get() = syncLock(lock) { ArrayList(inner.values) }
  override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
    get() = syncLock(lock) { inner.entries.mapTo(LinkedHashSet()) { Entry(it.key, it.value, this) } }

  private class Entry<K, V>(
    override val key: K,
    override var value: V,
    private val owner: ConcurrentHashMap<K, V>,
  ) : MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V {
      val old = value
      value = newValue
      owner.put(key, newValue)
      return old
    }

    override fun equals(other: Any?): Boolean =
      other is Map.Entry<*, *> && other.key == key && other.value == value

    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
  }
}

/** `java.util.concurrent.ThreadLocalRandom.current()`. */
object ThreadLocalRandom {
  fun current(): Random = Random.Default
}

/** `java.security.SecureRandom`; backed by the platform-seeded default generator. */
class SecureRandom {
  fun nextBytes(bytes: ByteArray) = Random.Default.nextBytes(bytes)
  fun nextInt(): Int = Random.Default.nextInt()
  fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
  fun nextLong(): Long = Random.Default.nextLong()
  fun nextBoolean(): Boolean = Random.Default.nextBoolean()
}
