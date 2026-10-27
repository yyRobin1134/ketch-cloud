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

package com.yunx.app.data.security

/**
 * Credential read/write entry point. YunX stores credentials in plain text since 2026-10 (see the
 * Android original for why Keystore field encryption was dropped), so this is the pass-through
 * half only; the legacy `yunx:v1:` ciphertext migration is Android-specific and not ported.
 */
internal interface CredentialCipher {
  fun encrypt(plaintext: String, purpose: String): String
  fun decrypt(stored: String, purpose: String): String
  fun isEncrypted(stored: String): Boolean
}

internal class PlaintextCredentialCipher : CredentialCipher {
  override fun encrypt(plaintext: String, purpose: String): String = plaintext

  override fun isEncrypted(stored: String): Boolean = false

  override fun decrypt(stored: String, purpose: String): String = stored

  companion object {
    /** One instance shared by every caller, as in the Android original. */
    val shared: CredentialCipher by lazy { PlaintextCredentialCipher() }
  }
}
