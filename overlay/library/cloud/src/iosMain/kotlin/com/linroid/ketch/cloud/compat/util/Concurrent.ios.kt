package com.linroid.ketch.cloud.compat.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import platform.posix.usleep
import kotlin.time.TimeSource

private val registryLock = NSLock()
private val locks = HashMap<Any, NSRecursiveLock>()

private fun lockFor(owner: Any): NSRecursiveLock {
  registryLock.lock()
  try {
    return locks.getOrPut(owner) { NSRecursiveLock() }
  } finally {
    registryLock.unlock()
  }
}

actual fun lockEnter(lock: Any) = lockFor(lock).lock()

actual fun lockExit(lock: Any) = lockFor(lock).unlock()

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private val origin = TimeSource.Monotonic.markNow()

actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

@OptIn(ExperimentalForeignApi::class)
actual fun sleepBlocking(millis: Long) {
  usleep((millis * 1000).toUInt())
}
