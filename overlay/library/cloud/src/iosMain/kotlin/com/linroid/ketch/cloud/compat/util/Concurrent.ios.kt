package com.linroid.ketch.cloud.compat.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import platform.posix.usleep
import kotlin.time.TimeSource

// One recursive lock for everything: the guarded sections are tiny cache reads and writes.
private val globalLock = NSRecursiveLock()

actual fun <T> syncLock(lock: Any, block: () -> T): T {
  globalLock.lock()
  try {
    return block()
  } finally {
    globalLock.unlock()
  }
}

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private val origin = TimeSource.Monotonic.markNow()

actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

@OptIn(ExperimentalForeignApi::class)
actual fun sleepBlocking(millis: Long) {
  usleep((millis * 1000).toUInt())
}
