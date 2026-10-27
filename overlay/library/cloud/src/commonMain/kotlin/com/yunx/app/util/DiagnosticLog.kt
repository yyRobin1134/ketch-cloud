/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.util

import com.linroid.ketch.cloud.compat.util.Log
import kotlin.concurrent.Volatile

/**
 * Port of YunX's `DiagnosticLog` for the Ketch cloud module. The original writes rotating log
 * files through an Android `Context`; here diagnostics are off by default and, when switched on,
 * go to [Log] (which the host app can route anywhere).
 */
object DiagnosticLog {
  const val DB = "db"
  const val CRYPTO = "crypto"
  const val DOWNLOAD = "download"
  const val WEBVIEW = "webview"
  const val NETWORK = "network"
  const val OPERATION = "operation"

  @Volatile
  private var enabled = false

  fun isEnabled(): Boolean = enabled

  fun setEnabled(value: Boolean) {
    enabled = value
  }

  fun log(module: String, event: String, level: String = "I", summary: String? = null) {
    if (!enabled) return
    val line = if (summary.isNullOrBlank()) event else "$event | ${LogRedactor.line(summary)}"
    when (level) {
      "W" -> Log.w("YunX-$module", line)
      "E" -> Log.e("YunX-$module", line)
      else -> Log.i("YunX-$module", line)
    }
  }

  fun event(module: String, event: String, summary: String? = null) = log(module, event, "I", summary)

  fun warn(module: String, event: String, summary: String? = null) = log(module, event, "W", summary)

  fun error(module: String, event: String, code: Any? = null, summary: String? = null) =
    log(module, event, "E", listOfNotNull(code?.let { "code=$it" }, summary).joinToString(" | "))

  fun network(
    method: String,
    url: String,
    code: Int,
    costMs: Long,
    requestBody: String? = null,
    responseBody: String? = null,
    error: String? = null,
  ) {
    if (!enabled) return
    val level = if (error != null || code !in 200..299) "W" else "I"
    log(
      NETWORK,
      if (error != null) "http_error" else "http_$code",
      level,
      buildString {
        append(method).append(' ').append(LogRedactor.url(url)).append(" | ").append(costMs).append("ms")
        if (error != null) append(" | err=").append(error)
        if (!requestBody.isNullOrBlank()) append(" | req=").append(requestBody.take(300))
        if (!responseBody.isNullOrBlank()) append(" | resp=").append(responseBody.take(300))
      },
    )
  }

  fun webview(event: String, url: String? = null, code: Any? = null, summary: String? = null) {
    log(
      WEBVIEW,
      event,
      if (code != null) "W" else "I",
      listOfNotNull(url?.let { "url=${LogRedactor.url(it)}" }, summary).joinToString(" | "),
    )
  }
}
