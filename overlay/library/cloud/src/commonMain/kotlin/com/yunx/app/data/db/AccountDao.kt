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

import com.linroid.ketch.cloud.compat.util.PlatformPrefs
import com.linroid.ketch.cloud.compat.util.currentTimeMillis
import com.linroid.ketch.cloud.compat.util.syncLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The account-table contract the YunX repositories were written against (a Room DAO there).
 * Rows are ordered primary-first, then most recently updated, the order the Room queries used.
 */
interface AccountDao<E : MultiAccountEntity> {
  fun observeAccount(): Flow<E?>
  suspend fun getAccount(): E?
  fun observeAccounts(): Flow<List<E>>
  suspend fun getAccounts(): List<E>
  suspend fun upsert(account: E)
  suspend fun setPrimary(id: String)
  suspend fun updateRemark(id: String, remark: String)
  suspend fun deleteById(id: String)
  suspend fun clear()
}

typealias QuarkAccountDao = AccountDao<QuarkAccountEntity>
typealias UCAccountDao = AccountDao<UCAccountEntity>
typealias XunleiAccountDao = AccountDao<XunleiAccountEntity>
typealias BaiduAccountDao = AccountDao<BaiduAccountEntity>
typealias C139AccountDao = AccountDao<C139AccountEntity>
typealias Pan123AccountDao = AccountDao<Pan123AccountEntity>
typealias Pan115AccountDao = AccountDao<Pan115AccountEntity>
typealias GuangYaAccountDao = AccountDao<GuangYaAccountEntity>
typealias ILanzouAccountDao = AccountDao<ILanzouAccountEntity>
typealias LanzouAccountDao = AccountDao<LanzouAccountEntity>

private val rowsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Keeps the rows of one table as a single JSON array in the platform preference store. */
internal class RowStore<E>(table: String, serializer: KSerializer<E>) {
  private val prefs = PlatformPrefs(table)
  private val listSerializer = ListSerializer(serializer)
  val rows = MutableStateFlow(load())

  private fun load(): List<E> =
    prefs.read(KEY)?.let { runCatching { rowsJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

  fun update(transform: (List<E>) -> List<E>) {
    syncLock(this) {
      val next = transform(rows.value)
      prefs.write(KEY, rowsJson.encodeToString(listSerializer, next))
      rows.value = next
    }
  }

  private companion object {
    const val KEY = "rows"
  }
}

class JsonAccountDao<E : MultiAccountEntity>(
  table: String,
  serializer: KSerializer<E>,
  private val withPrimary: (E, Boolean) -> E,
  private val withRemark: (E, String) -> E,
) : AccountDao<E> {
  private val store = RowStore(table, serializer)

  private fun ordered(rows: List<E>): List<E> =
    rows.sortedWith(compareByDescending<E> { it.isPrimary }.thenByDescending { it.updatedAt })

  override fun observeAccount(): Flow<E?> = store.rows.map { ordered(it).firstOrNull() }

  override suspend fun getAccount(): E? = ordered(store.rows.value).firstOrNull()

  override fun observeAccounts(): Flow<List<E>> = store.rows.map { ordered(it) }

  override suspend fun getAccounts(): List<E> = ordered(store.rows.value)

  override suspend fun upsert(account: E) =
    store.update { rows -> rows.filter { it.id != account.id } + account }

  override suspend fun setPrimary(id: String) =
    store.update { rows -> rows.map { withPrimary(it, it.id == id) } }

  override suspend fun updateRemark(id: String, remark: String) =
    store.update { rows -> rows.map { if (it.id == id) withRemark(it, remark) else it } }

  override suspend fun deleteById(id: String) = store.update { rows -> rows.filter { it.id != id } }

  override suspend fun clear() = store.update { emptyList() }
}

/** Video-parsing accounts: every platform shares one table, so queries filter by platform. */
class VideoAccountDao {
  private val store = RowStore("video_account", VideoAccountEntity.serializer())

  private fun ordered(rows: List<VideoAccountEntity>) =
    rows.sortedWith(
      compareBy<VideoAccountEntity> { it.platform }
        .thenByDescending { it.isPrimary }
        .thenByDescending { it.updatedAt },
    )

  fun observeAccounts(): Flow<List<VideoAccountEntity>> = store.rows.map { ordered(it) }

  suspend fun getAccounts(): List<VideoAccountEntity> = ordered(store.rows.value)

  fun observeAccountsOf(platform: String): Flow<List<VideoAccountEntity>> =
    store.rows.map { rows -> ordered(rows).filter { it.platform == platform } }

  suspend fun getActive(platform: String): VideoAccountEntity? =
    ordered(store.rows.value).firstOrNull { it.platform == platform }

  suspend fun countOf(platform: String): Int = store.rows.value.count { it.platform == platform }

  suspend fun upsert(account: VideoAccountEntity) =
    store.update { rows -> rows.filter { it.id != account.id } + account }

  suspend fun setPrimary(platform: String, id: String) = store.update { rows ->
    rows.map { if (it.platform == platform) it.copy(isPrimary = it.id == id) else it }
  }

  suspend fun updateRemark(id: String, remark: String) = store.update { rows ->
    rows.map { if (it.id == id) it.copy(remark = remark, updatedAt = currentTimeMillis()) else it }
  }

  suspend fun deleteById(id: String) = store.update { rows -> rows.filter { it.id != id } }

  suspend fun clearPlatform(platform: String) =
    store.update { rows -> rows.filter { it.platform != platform } }
}
