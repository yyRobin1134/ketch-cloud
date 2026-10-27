package com.linroid.ketch.cloud.compat.util

import platform.Foundation.NSUserDefaults

actual class PlatformPrefs actual constructor(private val name: String) {
  private val defaults = NSUserDefaults.standardUserDefaults
  private val prefix = "ketch.cloud.$name."

  actual fun read(key: String): String? = defaults.stringForKey(prefix + key)

  actual fun write(key: String, value: String?) {
    if (value == null) defaults.removeObjectForKey(prefix + key) else defaults.setObject(value, prefix + key)
  }

  actual fun keys(): Set<String> =
    defaults.dictionaryRepresentation().keys.mapNotNull { (it as? String)?.takeIf { k -> k.startsWith(prefix) }?.removePrefix(prefix) }.toSet()
}
