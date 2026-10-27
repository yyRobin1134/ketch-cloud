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

import com.linroid.ketch.cloud.compat.http.HttpUrl

/** Redacts capability-bearing URL paths, queries, fragments and user info. */
object LogRedactor {
  private val absoluteUrl = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
  private val secretAssignment = Regex(
    """(?i)\b(cookie|authorization|access[_-]?token|refresh[_-]?token|captcha[_-]?token|bduss|stoken|__puus|__pus|rmkey|signature|sign)\b(\s*[=:]\s*)([^\s,;]+)""",
  )

  fun url(value: Any?): String {
    val raw = value?.toString()?.takeIf { it.isNotBlank() } ?: return "<none>"
    val url = HttpUrl.parse(raw) ?: return if (raw.contains("://")) "<invalid-url>" else "<relative-url>"
    val defaultPort = (url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)
    val explicitPort = Regex(":(\d+)(?:/|\$)").containsMatchIn(raw.substringAfter("://").substringBefore('/'))
    val port = if (explicitPort && !defaultPort) ":${url.port}" else ""
    return "${url.scheme}://${url.host.lowercase()}$port"
  }

  fun line(value: String): String {
    val withoutUrls = absoluteUrl.replace(value) { match -> url(match.value) }
    return secretAssignment.replace(withoutUrls) { match ->
      "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
  }
}
