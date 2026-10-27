package com.linroid.ketch.cloud.compat.util

/**
 * Stand-ins for the slice of `android.content.Context` / `SharedPreferences` the YunX
 * account and device-fingerprint stores use. Values live in the platform's preference store
 * (`NSUserDefaults` on iOS); on the JVM they stay in memory, which is enough for tests.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("CompatContext")
class Context {
  val applicationContext: Context get() = this

  fun getSharedPreferences(name: String, @Suppress("UNUSED_PARAMETER") mode: Int = MODE_PRIVATE): SharedPreferences =
    PrefsImpl(name)

  companion object {
    const val MODE_PRIVATE = 0

    /** The one context the host app hands to the ported classes. */
    val default = Context()
  }
}

interface SharedPreferences {
  fun getString(key: String, defValue: String?): String?
  fun getBoolean(key: String, defValue: Boolean): Boolean
  fun getInt(key: String, defValue: Int): Int
  fun getLong(key: String, defValue: Long): Long
  fun contains(key: String): Boolean
  fun getAll(): Map<String, String>
  fun edit(): Editor

  interface Editor {
    fun putString(key: String, value: String?): Editor
    fun putBoolean(key: String, value: Boolean): Editor
    fun putInt(key: String, value: Int): Editor
    fun putLong(key: String, value: Long): Editor
    fun remove(key: String): Editor
    fun clear(): Editor
    fun apply()
    fun commit(): Boolean
  }
}

/** String-keyed persistence behind [SharedPreferences]; implemented per platform. */
expect class PlatformPrefs(name: String) {
  fun read(key: String): String?
  fun write(key: String, value: String?)
  fun keys(): Set<String>
}

private class PrefsImpl(private val name: String) : SharedPreferences {
  private val store = PlatformPrefs(name)

  override fun getString(key: String, defValue: String?): String? = store.read(key) ?: defValue
  override fun getBoolean(key: String, defValue: Boolean): Boolean = store.read(key)?.toBooleanStrictOrNull() ?: defValue
  override fun getInt(key: String, defValue: Int): Int = store.read(key)?.toIntOrNull() ?: defValue
  override fun getLong(key: String, defValue: Long): Long = store.read(key)?.toLongOrNull() ?: defValue
  override fun contains(key: String): Boolean = store.read(key) != null
  override fun getAll(): Map<String, String> = store.keys().associateWith { store.read(it).orEmpty() }

  override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
    private val pending = LinkedHashMap<String, String?>()
    private var clear = false

    override fun putString(key: String, value: String?) = apply { pending[key] = value }
    override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value.toString() }
    override fun putInt(key: String, value: Int) = apply { pending[key] = value.toString() }
    override fun putLong(key: String, value: Long) = apply { pending[key] = value.toString() }
    override fun remove(key: String) = apply { pending[key] = null }
    override fun clear() = apply { clear = true }

    override fun apply() {
      if (clear) store.keys().forEach { store.write(it, null) }
      for ((k, v) in pending) store.write(k, v)
    }

    override fun commit(): Boolean {
      apply()
      return true
    }
  }
}
