package com.linroid.ketch.cloud.compat.util

actual fun <T> syncLock(lock: Any, block: () -> T): T = kotlin.synchronized(lock, block)

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun nanoTime(): Long = System.nanoTime()

actual fun sleepBlocking(millis: Long) = Thread.sleep(millis)
