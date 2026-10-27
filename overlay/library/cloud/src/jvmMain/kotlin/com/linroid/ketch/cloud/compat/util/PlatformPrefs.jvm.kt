package com.linroid.ketch.cloud.compat.util

private val memory = HashMap<String, HashMap<String, String>>()

actual class PlatformPrefs actual constructor(name: String) {
  private val values: HashMap<String, String> = synchronized(memory) { memory.getOrPut(name) { HashMap() } }

  actual fun read(key: String): String? = synchronized(values) { values[key] }

  actual fun write(key: String, value: String?) {
    synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
  }

  actual fun keys(): Set<String> = synchronized(values) { values.keys.toSet() }
}
