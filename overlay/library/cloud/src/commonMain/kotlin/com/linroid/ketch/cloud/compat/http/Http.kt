package com.linroid.ketch.cloud.compat.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import okio.BufferedSink
import okio.Buffer

/**
 * An OkHttp-shaped client on top of Ktor, covering the subset of OkHttp the YunX parsers use:
 * blocking `newCall(request).execute()`, redirects, cookie jars, and the string/bytes body
 * readers. Responses are fully buffered; large file transfers belong to Ketch's own engine.
 */
enum class TimeUnit(internal val millis: Long) {
  MILLISECONDS(1),
  SECONDS(1_000),
  MINUTES(60_000),
  HOURS(3_600_000),
}

class MediaType private constructor(
  val type: String,
  val subtype: String,
  private val parameters: Map<String, String>,
) {
  fun charset(): String? = parameters["charset"]

  fun withCharset(charset: String): MediaType = MediaType(type, subtype, parameters + ("charset" to charset))

  override fun toString(): String = buildString {
    append(type).append('/').append(subtype)
    for ((k, v) in parameters) append("; ").append(k).append('=').append(v)
  }

  companion object {
    fun parse(text: String): MediaType? {
      val parts = text.split(';').map { it.trim() }
      val essence = parts.firstOrNull()?.split('/') ?: return null
      if (essence.size != 2 || essence[0].isEmpty() || essence[1].isEmpty()) return null
      val params = LinkedHashMap<String, String>()
      for (p in parts.drop(1)) {
        val kv = p.split('=', limit = 2)
        if (kv.size == 2) params[kv[0].trim().lowercase()] = kv[1].trim().trim('"')
      }
      return MediaType(essence[0].lowercase(), essence[1].lowercase(), params)
    }

    fun String.toMediaType(): MediaType =
      parse(this) ?: throw IllegalArgumentException("No subtype: $this")

    fun String.toMediaTypeOrNull(): MediaType? = parse(this)
  }
}

abstract class RequestBody {
  abstract fun contentType(): MediaType?
  open fun contentLength(): Long = -1L
  abstract fun writeTo(sink: BufferedSink)

  internal fun toBytes(): ByteArray {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readByteArray()
  }

  companion object {
    fun String.toRequestBody(contentType: MediaType? = null): RequestBody {
      val type = if (contentType != null && contentType.charset() == null) {
        contentType.withCharset("utf-8")
      } else {
        contentType
      }
      return bytesBody(encodeToByteArray(), type)
    }

    fun ByteArray.toRequestBody(contentType: MediaType? = null): RequestBody =
      bytesBody(this, contentType)

    fun create(contentType: MediaType?, content: String): RequestBody = content.toRequestBody(contentType)

    fun create(contentType: MediaType?, content: ByteArray): RequestBody = content.toRequestBody(contentType)

    private fun bytesBody(bytes: ByteArray, type: MediaType?): RequestBody = object : RequestBody() {
      override fun contentType(): MediaType? = type
      override fun contentLength(): Long = bytes.size.toLong()
      override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
      }
    }
  }
}

class FormBody private constructor(private val encoded: ByteArray) : RequestBody() {
  override fun contentType(): MediaType = MediaType.parse("application/x-www-form-urlencoded")!!
  override fun contentLength(): Long = encoded.size.toLong()
  override fun writeTo(sink: BufferedSink) {
    sink.write(encoded)
  }

  class Builder {
    private val pairs = ArrayList<Pair<String, String>>()

    fun add(name: String, value: String): Builder = apply { pairs += name to value }

    fun addEncoded(name: String, value: String): Builder = apply { pairs += name to value }

    fun build(): FormBody = FormBody(
      pairs.joinToString("&") { (k, v) -> percentEncodeForm(k) + "=" + percentEncodeForm(v) }
        .encodeToByteArray(),
    )
  }
}

class Headers private constructor(private val namesAndValues: List<String>) {
  val size: Int get() = namesAndValues.size / 2

  fun name(index: Int): String = namesAndValues[index * 2]

  fun value(index: Int): String = namesAndValues[index * 2 + 1]

  /** Returns the last value for [name], matching OkHttp. */
  operator fun get(name: String): String? {
    for (i in size - 1 downTo 0) if (name(i).equals(name, ignoreCase = true)) return value(i)
    return null
  }

  fun values(name: String): List<String> =
    (0 until size).filter { name(it).equals(name, ignoreCase = true) }.map { value(it) }

  fun names(): Set<String> = (0 until size).map { name(it) }.toCollection(LinkedHashSet())

  fun toMultimap(): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    for (i in 0 until size) out.getOrPut(name(i).lowercase()) { ArrayList() }.add(value(i))
    return out
  }

  operator fun iterator(): Iterator<Pair<String, String>> =
    (0 until size).map { name(it) to value(it) }.iterator()

  fun newBuilder(): Builder = Builder().also { it.list.addAll(namesAndValues) }

  override fun toString(): String = buildString {
    for (i in 0 until size) append(name(i)).append(": ").append(value(i)).append('\n')
  }

  class Builder {
    internal val list = ArrayList<String>()

    fun add(name: String, value: String): Builder = apply {
      list += name
      list += value.trim()
    }

    fun set(name: String, value: String): Builder = apply {
      removeAll(name)
      add(name, value)
    }

    fun removeAll(name: String): Builder = apply {
      var i = 0
      while (i < list.size) {
        if (list[i].equals(name, ignoreCase = true)) {
          list.removeAt(i)
          list.removeAt(i)
        } else {
          i += 2
        }
      }
    }

    fun get(name: String): String? {
      var i = list.size - 2
      while (i >= 0) {
        if (list[i].equals(name, ignoreCase = true)) return list[i + 1]
        i -= 2
      }
      return null
    }

    fun build(): Headers = Headers(list.toList())
  }

  companion object {
    fun headersOf(vararg namesAndValues: String): Headers {
      require(namesAndValues.size % 2 == 0) { "Expected alternating header names and values" }
      return Builder().also { b ->
        for (i in namesAndValues.indices step 2) b.add(namesAndValues[i], namesAndValues[i + 1])
      }.build()
    }

    fun Map<String, String>.toHeaders(): Headers =
      Builder().also { b -> for ((k, v) in this) b.add(k, v) }.build()
  }
}

class HttpUrl private constructor(private val text: String) {
  private val schemeEnd = text.indexOf("://")
  private val authorityStart = schemeEnd + 3
  private val pathStart = text.indexOf('/', authorityStart).let { if (it < 0) text.length else it }
  private val queryStart = text.indexOf('?').let { if (it < 0) text.length else it }
  private val fragmentStart = text.indexOf('#').let { if (it < 0) text.length else it }

  val scheme: String get() = text.substring(0, schemeEnd)

  val host: String
    get() {
      val authority = text.substring(authorityStart, minOf(pathStart, queryStart, fragmentStart))
      val noUser = authority.substringAfterLast('@')
      return if (noUser.startsWith("[")) noUser.substringBefore(']') + "]" else noUser.substringBefore(':')
    }

  val port: Int
    get() {
      val authority = text.substring(authorityStart, minOf(pathStart, queryStart, fragmentStart))
      val explicit = authority.substringAfterLast('@').substringAfterLast(':', "").toIntOrNull()
      return explicit ?: if (scheme == "https") 443 else 80
    }

  val isHttps: Boolean get() = scheme == "https"

  val encodedPath: String
    get() = text.substring(pathStart, minOf(queryStart, fragmentStart)).ifEmpty { "/" }

  val query: String?
    get() = if (queryStart >= fragmentStart) null else text.substring(queryStart + 1, fragmentStart)

  fun queryParameter(name: String): String? =
    query?.split('&')?.firstNotNullOfOrNull { pair ->
      val kv = pair.split('=', limit = 2)
      if (percentDecode(kv[0]) == name) percentDecode(kv.getOrElse(1) { "" }) else null
    }

  fun newBuilder(): Builder = Builder(text)

  /** Resolves [link] (absolute or relative) against this URL, like `HttpUrl.resolve`. */
  fun resolve(link: String): HttpUrl? {
    val resolved = when {
      link.startsWith("http://", true) || link.startsWith("https://", true) -> link
      link.startsWith("//") -> "$scheme:$link"
      link.startsWith("/") -> text.substring(0, pathStart) + link
      link.startsWith("?") -> text.substring(0, minOf(queryStart, fragmentStart)) + link
      link.startsWith("#") -> text.substring(0, fragmentStart) + link
      else -> {
        val base = text.substring(0, minOf(queryStart, fragmentStart))
        base.substring(0, base.lastIndexOf('/').coerceAtLeast(pathStart)) + "/" + link
      }
    }
    return parse(resolved)
  }

  override fun toString(): String = text
  override fun equals(other: Any?): Boolean = other is HttpUrl && other.text == text
  override fun hashCode(): Int = text.hashCode()

  class Builder internal constructor(private var base: String) {
    constructor() : this("")

    fun scheme(value: String): Builder = apply { base = value + "://" + base.substringAfter("://", "") }

    fun host(value: String): Builder = apply {
      val rest = base.substringAfter("://", "")
      val scheme = base.substringBefore("://", "https")
      val tail = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) "" else rest.substring(it) }
      base = "$scheme://$value$tail"
    }

    fun addQueryParameter(name: String, value: String?): Builder = apply {
      val sep = if (base.contains('?')) '&' else '?'
      base += sep + percentEncodeForm(name) + (if (value != null) "=" + percentEncodeForm(value) else "")
    }

    fun build(): HttpUrl = parse(base) ?: throw IllegalArgumentException("Invalid URL: $base")
  }

  companion object {
    fun parse(text: String): HttpUrl? {
      val trimmed = text.trim()
      val ok = (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) &&
        trimmed.substringAfter("://").isNotEmpty()
      return if (ok) HttpUrl(trimmed.substring(0, trimmed.indexOf("://")).lowercase() + trimmed.substring(trimmed.indexOf("://"))) else null
    }

    fun String.toHttpUrl(): HttpUrl = parse(this) ?: throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '$this'")

    fun String.toHttpUrlOrNull(): HttpUrl? = parse(this)
  }
}

class Cookie(
  val name: String,
  val value: String,
  val expiresAt: Long = Long.MAX_VALUE,
  val domain: String,
  val path: String = "/",
  val secure: Boolean = false,
  val httpOnly: Boolean = false,
  val hostOnly: Boolean = false,
) {
  override fun toString(): String = "$name=$value"

  companion object {
    /** Parses one `Set-Cookie` header received from [url]; returns null when it is malformed. */
    fun parse(url: HttpUrl, setCookie: String): Cookie? {
      val parts = setCookie.split(';').map { it.trim() }
      val nameValue = parts.firstOrNull()?.split('=', limit = 2) ?: return null
      if (nameValue.size != 2 || nameValue[0].isEmpty()) return null
      var domain: String? = null
      var path = "/"
      var secure = false
      var httpOnly = false
      for (attr in parts.drop(1)) {
        val kv = attr.split('=', limit = 2)
        when (kv[0].lowercase()) {
          "domain" -> domain = kv.getOrNull(1)?.trimStart('.')?.lowercase()
          "path" -> if (kv.getOrNull(1)?.startsWith("/") == true) path = kv[1]
          "secure" -> secure = true
          "httponly" -> httpOnly = true
        }
      }
      return Cookie(
        name = nameValue[0],
        value = nameValue[1],
        domain = domain ?: url.host,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = domain == null,
      )
    }
  }
}

interface CookieJar {
  fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>)
  fun loadForRequest(url: HttpUrl): List<Cookie>

  companion object {
    val NO_COOKIES: CookieJar = object : CookieJar {
      override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
      override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }
  }
}

class Request private constructor(
  val url: HttpUrl,
  val method: String,
  val headers: Headers,
  val body: RequestBody?,
) {
  fun header(name: String): String? = headers[name]

  fun newBuilder(): Builder = Builder().also {
    it.url = url
    it.method = method
    it.headers = headers.newBuilder()
    it.body = body
  }

  override fun toString(): String = "Request{method=$method, url=$url}"

  class Builder {
    internal var url: HttpUrl? = null
    internal var method: String = "GET"
    internal var headers: Headers.Builder = Headers.Builder()
    internal var body: RequestBody? = null

    fun url(value: HttpUrl): Builder = apply { url = value }

    fun url(value: String): Builder = apply {
      url = HttpUrl.parse(value) ?: throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '$value'")
    }

    fun header(name: String, value: String): Builder = apply { headers.set(name, value) }

    fun addHeader(name: String, value: String): Builder = apply { headers.add(name, value) }

    fun removeHeader(name: String): Builder = apply { headers.removeAll(name) }

    fun headers(value: Headers): Builder = apply { headers = value.newBuilder() }

    fun get(): Builder = method("GET", null)

    fun head(): Builder = method("HEAD", null)

    fun post(body: RequestBody): Builder = method("POST", body)

    fun put(body: RequestBody): Builder = method("PUT", body)

    fun patch(body: RequestBody): Builder = method("PATCH", body)

    fun delete(body: RequestBody? = null): Builder = method("DELETE", body)

    fun method(method: String, body: RequestBody?): Builder = apply {
      this.method = method
      this.body = body
    }

    fun build(): Request = Request(
      url = checkNotNull(url) { "url == null" },
      method = method,
      headers = headers.build(),
      body = body,
    )
  }
}

class ResponseBody internal constructor(
  private val bytes: ByteArray,
  private val type: MediaType?,
) : AutoCloseable {
  fun contentType(): MediaType? = type

  fun contentLength(): Long = bytes.size.toLong()

  fun bytes(): ByteArray = bytes

  fun string(): String = when (type?.charset()?.lowercase()) {
    "iso-8859-1", "latin1", "us-ascii", "ascii" -> CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
    else -> bytes.decodeToString().removePrefix("﻿")
  }

  override fun close() {}
}

class Response internal constructor(
  val request: Request,
  val code: Int,
  val message: String,
  val headers: Headers,
  val body: ResponseBody?,
  val priorResponse: Response?,
) : AutoCloseable {
  val isSuccessful: Boolean get() = code in 200..299

  val isRedirect: Boolean get() = code in REDIRECT_CODES

  fun header(name: String, defaultValue: String? = null): String? = headers[name] ?: defaultValue

  fun headers(name: String): List<String> = headers.values(name)

  override fun close() {}

  override fun toString(): String = "Response{code=$code, url=${request.url}}"

  internal companion object {
    val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
  }
}

class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

class Call internal constructor(private val client: OkHttpClient, private val request: Request) {
  fun request(): Request = request

  fun execute(): Response = runBlockingCompat { client.executeSuspend(request) }

  suspend fun await(): Response = client.executeSuspend(request)
}

class OkHttpClient private constructor(private val config: Config, internal val engine: HttpClient) {
  internal class Config(
    var followRedirects: Boolean = true,
    var followSslRedirects: Boolean = true,
    var cookieJar: CookieJar = CookieJar.NO_COOKIES,
    var connectTimeoutMs: Long = 10_000,
    var readTimeoutMs: Long = 10_000,
    var callTimeoutMs: Long = 0,
  ) {
    fun copy() = Config(followRedirects, followSslRedirects, cookieJar, connectTimeoutMs, readTimeoutMs, callTimeoutMs)
  }

  constructor() : this(Config(), sharedEngine)

  val followRedirects: Boolean get() = config.followRedirects
  val cookieJar: CookieJar get() = config.cookieJar

  fun newCall(request: Request): Call = Call(this, request)

  fun newBuilder(): Builder = Builder(config.copy(), engine)

  suspend fun executeSuspend(request: Request): Response {
    var current = request
    var prior: Response? = null
    var hops = 0
    while (true) {
      val response = executeOnce(current, prior)
      val location = response.header("Location")
      if (!config.followRedirects || !response.isRedirect || location == null) return response
      if (++hops > MAX_REDIRECTS) throw IOException("Too many follow-up requests: $hops")
      val next = current.url.resolve(location) ?: return response
      if (next.scheme != current.url.scheme && !config.followSslRedirects) return response
      val sameHost = next.host == current.url.host
      val builder = current.newBuilder().url(next)
      if (response.code == 307 || response.code == 308) {
        builder.method(current.method, current.body)
      } else if (current.method != "GET" && current.method != "HEAD") {
        builder.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
      }
      if (!sameHost) builder.removeHeader("Authorization")
      builder.removeHeader("Cookie").removeHeader("Host")
      prior = response
      current = builder.build()
    }
  }

  private suspend fun executeOnce(request: Request, prior: Response?): Response {
    val url = request.url
    val cookieHeader = config.cookieJar.loadForRequest(url)
      .joinToString("; ") { "${it.name}=${it.value}" }
    val bodyBytes = request.body?.toBytes()
    val raw: HttpResponse = try {
      engine.request(url.toString()) {
        applyTo(this, request, bodyBytes, cookieHeader)
        timeout {
          connectTimeoutMillis = config.connectTimeoutMs
          socketTimeoutMillis = config.readTimeoutMs
          if (config.callTimeoutMs > 0) requestTimeoutMillis = config.callTimeoutMs
        }
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      throw IOException(e.message ?: e::class.simpleName, e)
    }
    val headerList = ArrayList<String>()
    raw.headers.forEach { name, values -> values.forEach { headerList += name; headerList += it } }
    val headers = Headers.headersOf(*headerList.toTypedArray())
    val saved = headers.values("Set-Cookie").mapNotNull { Cookie.parse(url, it) }
    if (saved.isNotEmpty()) config.cookieJar.saveFromResponse(url, saved)
    val bytes = if (request.method == "HEAD") ByteArray(0) else raw.bodyAsBytes()
    return Response(
      request = request,
      code = raw.status.value,
      message = raw.status.description,
      headers = headers,
      body = ResponseBody(bytes, headers["Content-Type"]?.let { MediaType.parse(it) }),
      priorResponse = prior,
    )
  }

  private fun applyTo(builder: HttpRequestBuilder, request: Request, body: ByteArray?, cookies: String) {
    builder.method = HttpMethod.parse(request.method)
    var sawUserAgent = false
    var sawCookie = false
    for ((name, value) in request.headers) {
      if (name.equals("Content-Length", true) || name.equals("Host", true)) continue
      if (name.equals("Content-Type", true)) {
        // Ktor reserves Content-Type for the engine; it must be set through contentType().
        ContentType.parse(value).let { builder.contentType(it) }
        continue
      }
      if (name.equals("User-Agent", true)) sawUserAgent = true
      if (name.equals("Cookie", true)) sawCookie = true
      builder.headers.append(name, value)
    }
    if (!sawUserAgent) builder.headers.append("User-Agent", DEFAULT_USER_AGENT)
    if (cookies.isNotEmpty() && !sawCookie) builder.headers.append("Cookie", cookies)
    if (body != null) {
      val type = request.body?.contentType()
      if (type != null && request.header("Content-Type") == null) {
        builder.contentType(ContentType.parse(type.toString()))
      }
      builder.setBody(body)
    }
  }

  class Builder internal constructor(private val config: Config, private val engine: HttpClient) {
    constructor() : this(Config(), sharedEngine)

    private fun millis(duration: Long, unit: TimeUnit) = duration * unit.millis

    fun followRedirects(value: Boolean): Builder = apply { config.followRedirects = value }

    fun followSslRedirects(value: Boolean): Builder = apply { config.followSslRedirects = value }

    fun cookieJar(value: CookieJar): Builder = apply { config.cookieJar = value }

    fun connectTimeout(duration: Long, unit: TimeUnit): Builder = apply { config.connectTimeoutMs = millis(duration, unit) }

    fun readTimeout(duration: Long, unit: TimeUnit): Builder = apply { config.readTimeoutMs = millis(duration, unit) }

    fun writeTimeout(duration: Long, unit: TimeUnit): Builder = this

    fun callTimeout(duration: Long, unit: TimeUnit): Builder = apply { config.callTimeoutMs = millis(duration, unit) }

    fun build(): OkHttpClient = OkHttpClient(config, engine)
  }

  companion object {
    private const val MAX_REDIRECTS = 20
    private const val DEFAULT_USER_AGENT = "okhttp/4.12.0"

    private val sharedEngine: HttpClient by lazy {
      HttpClient {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
      }
    }

    /** For tests: a client whose requests go to [engine] instead of the network. */
    fun withEngine(engine: HttpClient): OkHttpClient = OkHttpClient(Config(), engine)
  }
}

/** Form-encodes like `application/x-www-form-urlencoded`: spaces become `+`. */
internal fun percentEncodeForm(text: String): String {
  val out = StringBuilder()
  for (byte in text.encodeToByteArray()) {
    val c = byte.toInt() and 0xFF
    val ch = c.toChar()
    when {
      ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> out.append(ch)
      ch == ' ' -> out.append('+')
      else -> out.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
    }
  }
  return out.toString()
}

internal fun percentDecode(text: String): String {
  val bytes = ArrayList<Byte>()
  var i = 0
  while (i < text.length) {
    val ch = text[i]
    when {
      ch == '+' -> { bytes += ' '.code.toByte(); i++ }
      ch == '%' && i + 2 < text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
        bytes += text.substring(i + 1, i + 3).toInt(16).toByte()
        i += 3
      }
      else -> { bytes += ch.toString().encodeToByteArray().toList(); i++ }
    }
  }
  return bytes.toByteArray().decodeToString()
}

/** Runs [block] to completion on the calling thread; the YunX code is written in blocking style. */
internal expect fun <T> runBlockingCompat(block: suspend () -> T): T
