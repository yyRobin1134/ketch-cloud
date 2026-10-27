package com.linroid.ketch.cloud.compat

import com.linroid.ketch.cloud.compat.crypto.Cipher
import com.linroid.ketch.cloud.compat.crypto.IvParameterSpec
import com.linroid.ketch.cloud.compat.crypto.MessageDigest
import com.linroid.ketch.cloud.compat.crypto.SecretKeySpec
import com.linroid.ketch.cloud.compat.crypto.toHex
import com.linroid.ketch.cloud.compat.http.Cookie
import com.linroid.ketch.cloud.compat.http.CookieJar
import com.linroid.ketch.cloud.compat.http.FormBody
import com.linroid.ketch.cloud.compat.http.HttpUrl
import com.linroid.ketch.cloud.compat.http.MediaType.Companion.toMediaType
import com.linroid.ketch.cloud.compat.http.OkHttpClient
import com.linroid.ketch.cloud.compat.http.Request
import com.linroid.ketch.cloud.compat.http.RequestBody.Companion.toRequestBody
import com.linroid.ketch.cloud.compat.json.JSONArray
import com.linroid.ketch.cloud.compat.json.JSONException
import com.linroid.ketch.cloud.compat.json.JSONObject
import com.linroid.ketch.cloud.compat.util.AndroidBase64
import com.linroid.ketch.cloud.compat.util.URLEncoder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
  private fun String.hex(): ByteArray =
    ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

  @Test
  fun digests_match_known_vectors() {
    fun digest(alg: String, text: String) = MessageDigest.getInstance(alg).digest(text.encodeToByteArray()).toHex()
    assertEquals("d41d8cd98f00b204e9800998ecf8427e", digest("MD5", ""))
    assertEquals("900150983cd24fb0d6963f7d28e17f72", digest("MD5", "abc"))
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest("SHA-1", "abc"))
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest("SHA-256", "abc"))
    // Message long enough to need a second block.
    assertEquals(
      "57edf4a22be3c955ac49da2e2107b67a",
      digest("MD5", "12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
    )
  }

  @Test
  fun aes_matches_fips_197() {
    val plain = "00112233445566778899aabbccddeeff".hex()
    val key128 = SecretKeySpec("000102030405060708090a0b0c0d0e0f".hex(), "AES")
    val key256 = SecretKeySpec("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hex(), "AES")
    val ecb = Cipher.getInstance("AES/ECB/NoPadding")
    ecb.init(Cipher.ENCRYPT_MODE, key128)
    assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", ecb.doFinal(plain).toHex())
    ecb.init(Cipher.ENCRYPT_MODE, key256)
    assertEquals("8ea2b7ca516745bfeafc49904b496089", ecb.doFinal(plain).toHex())
    ecb.init(Cipher.DECRYPT_MODE, key256)
    assertEquals(plain.toHex(), ecb.doFinal("8ea2b7ca516745bfeafc49904b496089".hex()).toHex())
  }

  @Test
  fun aes_cbc_round_trips_with_padding() {
    val key = SecretKeySpec("0123456789abcdef".encodeToByteArray(), "AES")
    val iv = IvParameterSpec("fedcba9876543210".encodeToByteArray())
    val text = "hello yunx, this is longer than one block"
    val enc = Cipher.getInstance("AES/CBC/PKCS5Padding")
    enc.init(Cipher.ENCRYPT_MODE, key, iv)
    val cipherText = enc.doFinal(text.encodeToByteArray())
    assertEquals(48, cipherText.size)
    val dec = Cipher.getInstance("AES/CBC/PKCS5Padding")
    dec.init(Cipher.DECRYPT_MODE, key, iv)
    assertEquals(text, dec.doFinal(cipherText).decodeToString())
  }
}

class JsonTest {
  @Test
  fun parses_and_coerces_like_android() {
    val o = JSONObject("""{"a":1,"b":"2","c":true,"d":null,"e":{"x":[1,2,{"y":"z"}]},"f":3.5,"g":9999999999}""")
    assertEquals(1, o.getInt("a"))
    assertEquals(2, o.optInt("b"))
    assertEquals("1", o.optString("a"))
    assertEquals("null", o.optString("d"))
    assertTrue(o.isNull("d"))
    assertEquals("", o.optString("missing"))
    assertEquals(7, o.optInt("missing", 7))
    assertEquals(true, o.getBoolean("c"))
    assertEquals(3.5, o.getDouble("f"))
    assertEquals(9999999999L, o.getLong("g"))
    assertEquals("z", o.getJSONObject("e").getJSONArray("x").getJSONObject(2).getString("y"))
    assertFailsWith<JSONException> { o.getString("missing") }
    assertFailsWith<JSONException> { o.getJSONObject("a") }
    assertNull(o.optJSONObject("a"))
  }

  @Test
  fun writes_compact_json() {
    val o = JSONObject().put("k", "v\"q").put("n", 1).put("list", JSONArray().put(1).put("two"))
    assertEquals("""{"k":"v\"q","n":1,"list":[1,"two"]}""", o.toString())
    assertEquals(o.toString(), JSONObject(o.toString()).toString())
  }
}

class UtilTest {
  @Test
  fun url_encoder_matches_java() {
    assertEquals("a+b%26c%3D%C3%A9", URLEncoder.encode("a b&c=é", "UTF-8"))
  }

  @Test
  fun base64_flags() {
    val data = ByteArray(60) { it.toByte() }
    val wrapped = AndroidBase64.encodeToString(data, AndroidBase64.DEFAULT)
    assertTrue(wrapped.endsWith("\n"))
    assertEquals(data.toList(), AndroidBase64.decode(wrapped, AndroidBase64.DEFAULT).toList())
    val flat = AndroidBase64.encodeToString(byteArrayOf(-5, -1), AndroidBase64.NO_WRAP or AndroidBase64.URL_SAFE or AndroidBase64.NO_PADDING)
    assertEquals("-_8", flat)
  }
}

class HttpTest {
  @Test
  fun sends_headers_and_reads_body() = runTest {
    var seenUa: String? = null
    var seenType: String? = null
    var seenBody: String? = null
    val http = OkHttpClient.withEngine(
      HttpClient(
        MockEngine { request ->
          seenUa = request.headers[HttpHeaders.UserAgent]
          seenType = request.body.contentType?.toString()
          seenBody = request.body.toByteArray().decodeToString()
          respond("""{"ok":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
        },
      ) { followRedirects = false },
    )
    val request = Request.Builder()
      .url("https://example.com/api?x=1")
      .header("User-Agent", "yunx")
      .post("""{"a":1}""".toRequestBody("application/json".toMediaType()))
      .build()
    val response = http.newCall(request).await()
    assertEquals(200, response.code)
    assertTrue(response.isSuccessful)
    assertEquals(1, JSONObject(response.body!!.string()).getInt("ok"))
    assertEquals("yunx", seenUa)
    assertTrue(seenType!!.startsWith("application/json"))
    assertEquals("""{"a":1}""", seenBody)
  }

  @Test
  fun follows_redirects_and_keeps_prior_response() = runTest {
    val http = OkHttpClient.withEngine(
      HttpClient(
        MockEngine { request ->
          if (request.url.encodedPath == "/start") {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/end"))
          } else {
            respond("done", HttpStatusCode.OK)
          }
        },
      ) { followRedirects = false },
    )
    val followed = http.newCall(Request.Builder().url("https://example.com/start").build()).await()
    assertEquals("done", followed.body!!.string())
    assertEquals(302, followed.priorResponse!!.code)
    val manual = http.newBuilder().followRedirects(false).build()
      .newCall(Request.Builder().url("https://example.com/start").build()).await()
    assertEquals(302, manual.code)
    assertEquals("/end", manual.header("Location"))
  }

  @Test
  fun cookie_jar_round_trips() = runTest {
    val stored = ArrayList<Cookie>()
    var sentCookie: String? = null
    val http = OkHttpClient.withEngine(
      HttpClient(
        MockEngine { request ->
          sentCookie = request.headers[HttpHeaders.Cookie]
          respond("", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "sid=abc; Path=/; Secure"))
        },
      ) { followRedirects = false },
    ).newBuilder().cookieJar(object : CookieJar {
      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        stored += cookies
      }

      override fun loadForRequest(url: HttpUrl): List<Cookie> = stored.toList()
    }).build()
    http.newCall(Request.Builder().url("https://example.com/").build()).await()
    assertEquals(1, stored.size)
    assertEquals("sid", stored[0].name)
    http.newCall(Request.Builder().url("https://example.com/").build()).await()
    assertEquals("sid=abc", sentCookie)
  }

  @Test
  fun form_body_is_urlencoded() {
    val body = FormBody.Builder().add("a b", "c&d").build()
    assertEquals("a+b=c%26d", body.toBytesForTest().decodeToString())
  }

  @Test
  fun http_url_parts() {
    val url = HttpUrl.parse("https://Example.com:8443/a/b?x=1&y=%E4%BD%A0")!!
    assertEquals("https", url.scheme)
    assertEquals("Example.com", url.host)
    assertEquals(8443, url.port)
    assertEquals("/a/b", url.encodedPath)
    assertEquals("你", url.queryParameter("y"))
    assertEquals("https://Example.com:8443/c", url.resolve("/c").toString())
  }
}

private fun FormBody.toBytesForTest(): ByteArray {
  val buffer = okio.Buffer()
  writeTo(buffer)
  return buffer.readByteArray()
}
