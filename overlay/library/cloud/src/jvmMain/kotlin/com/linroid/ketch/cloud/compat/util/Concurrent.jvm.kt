package com.linroid.ketch.cloud.compat.util

import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock

private val locks = WeakHashMap<Any, ReentrantLock>()

private fun lockFor(owner: Any): ReentrantLock =
  synchronized(locks) { locks.getOrPut(owner) { ReentrantLock() } }

actual fun lockEnter(lock: Any) = lockFor(lock).lock()

actual fun lockExit(lock: Any) = lockFor(lock).unlock()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun nanoTime(): Long = System.nanoTime()

actual fun sleepBlocking(millis: Long) = Thread.sleep(millis)
