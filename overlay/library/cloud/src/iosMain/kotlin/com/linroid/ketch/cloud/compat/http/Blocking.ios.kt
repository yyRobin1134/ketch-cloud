package com.linroid.ketch.cloud.compat.http

import kotlinx.coroutines.runBlocking

internal actual fun <T> runBlockingCompat(block: suspend () -> T): T = runBlocking { block() }
