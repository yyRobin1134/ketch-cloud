package com.linroid.ketch.cloud.compat.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** `Dispatchers.IO` for common code, which kotlinx.coroutines only declares per platform. */
expect val Dispatchers.IO: CoroutineDispatcher
