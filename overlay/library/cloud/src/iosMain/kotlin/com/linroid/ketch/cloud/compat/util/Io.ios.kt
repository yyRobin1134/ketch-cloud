package com.linroid.ketch.cloud.compat.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default

// Not Dispatchers.IO: inside this declaration that name resolves to the property itself, which recursed until the stack overflowed.
