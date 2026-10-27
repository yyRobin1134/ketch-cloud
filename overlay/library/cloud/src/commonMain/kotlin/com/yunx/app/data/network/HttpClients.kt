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

package com.yunx.app.data.network

import com.linroid.ketch.cloud.compat.http.OkHttpClient
import com.linroid.ketch.cloud.compat.http.TimeUnit

/**
 * Port of YunX's `HttpClients` for the Ketch cloud module. The Android original also tuned
 * OkHttp's dispatcher and connection pool for chunked downloads; here the shared Ktor engine
 * handles pooling, and file transfers are done by Ketch's own download engine.
 */
object HttpClients {

  private val api: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  /** Client for platform APIs: login, share parsing and direct-link requests. */
  fun apiClient(): OkHttpClient = api

  /** Same client as [apiClient]; Ketch downloads the resolved links itself. */
  fun downloadClient(): OkHttpClient = api

  /** The Android original configures a global HTTP proxy; not supported here yet. */
  @Suppress("UNUSED_PARAMETER")
  fun setProxy(host: String?, port: Int) {
  }

  fun evictIdleConnections() {
  }
}
