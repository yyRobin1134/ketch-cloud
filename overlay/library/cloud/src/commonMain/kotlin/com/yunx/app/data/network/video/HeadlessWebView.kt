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

package com.yunx.app.data.network.video

import kotlin.concurrent.Volatile

/** What a platform's hidden web view must provide: load a page, wait until it is ready, read a value. */
interface HeadlessWebEngine {
  suspend fun loadAndExtract(
    url: String,
    userAgent: String,
    readyCheckJs: String,
    extractJs: String,
    timeoutMs: Long,
    pollIntervalMs: Long,
    extraDocumentStartJs: List<String>,
  ): String?
}

/**
 * Port of YunX's `HeadlessWebView`. The Android original attaches a 1x1 transparent `WebView` to
 * the foreground Activity; here the host app installs an engine (a `WKWebView` on iOS) through
 * [engine]. Without one every call returns null, the same result as a page that never rendered.
 *
 * Deliberately not ported: the Android version also rewrites `navigator.platform` and the
 * Sec-CH-UA client hints to match its desktop user agent so that bot checks accept the page.
 * Engines here load the page as the platform really is.
 */
object HeadlessWebView {
  @Volatile
  var engine: HeadlessWebEngine? = null

  suspend fun loadAndExtract(
    url: String,
    userAgent: String,
    readyCheckJs: String,
    extractJs: String,
    timeoutMs: Long = 25_000L,
    pollIntervalMs: Long = 350L,
    extraDocumentStartJs: List<String> = emptyList(),
  ): String? = engine?.loadAndExtract(
    url, userAgent, readyCheckJs, extractJs, timeoutMs, pollIntervalMs, extraDocumentStartJs,
  )
}
