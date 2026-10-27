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

package com.yunx.app.data.db

import com.linroid.ketch.cloud.compat.util.Context

/**
 * The account database. The Android original is a Room database with migrations and
 * field-level encryption; here every table is a [JsonAccountDao] over the platform preference
 * store. The accessor names match the original so the repositories port unchanged.
 */
class AppDatabase private constructor() {
  private val quark = JsonAccountDao(
    "quark_account", QuarkAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val uc = JsonAccountDao(
    "uc_account", UCAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val xunlei = JsonAccountDao(
    "xunlei_account", XunleiAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val baidu = JsonAccountDao(
    "baidu_account", BaiduAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val c139 = JsonAccountDao(
    "c139_account", C139AccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val pan123 = JsonAccountDao(
    "pan123_account", Pan123AccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val pan115 = JsonAccountDao(
    "pan115_account", Pan115AccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val guangya = JsonAccountDao(
    "guangya_account", GuangYaAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val ilanzou = JsonAccountDao(
    "ilanzou_account", ILanzouAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val lanzou = JsonAccountDao(
    "lanzou_account", LanzouAccountEntity.serializer(),
    { e, p -> e.copy(isPrimary = p) }, { e, r -> e.copy(remark = r) },
  )
  private val video = VideoAccountDao()

  fun quarkAccountDao(): QuarkAccountDao = quark
  fun ucAccountDao(): UCAccountDao = uc
  fun xunleiAccountDao(): XunleiAccountDao = xunlei
  fun baiduAccountDao(): BaiduAccountDao = baidu
  fun c139AccountDao(): C139AccountDao = c139
  fun pan123AccountDao(): Pan123AccountDao = pan123
  fun pan115AccountDao(): Pan115AccountDao = pan115
  fun guangyaAccountDao(): GuangYaAccountDao = guangya
  fun ilanzouAccountDao(): ILanzouAccountDao = ilanzou
  fun lanzouAccountDao(): LanzouAccountDao = lanzou
  fun videoAccountDao(): VideoAccountDao = video

  companion object {
    private val instance: AppDatabase by lazy { AppDatabase() }

    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context = Context.default): AppDatabase = instance
  }
}
