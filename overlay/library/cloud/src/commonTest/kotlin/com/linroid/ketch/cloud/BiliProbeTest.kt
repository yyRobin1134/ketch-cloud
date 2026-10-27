package com.linroid.ketch.cloud

import com.yunx.app.data.network.video.VideoHttp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

/** Temporary: reports what Bilibili answers to the requests the parser makes, per header set. */
class BiliProbeTest {
  private val view = "https://api.bilibili.com/x/web-interface/view?bvid=BV1gfHT6aEqv"

  private suspend fun probe(name: String, url: String, headers: Map<String, String>): String = try {
    val r = VideoHttp.request(url, headers = headers)
    "$name -> ${r.status} ${r.body.take(120).replace("\n", " ")}"
  } catch (e: Exception) {
    "$name -> EXC ${e.message}"
  }

  @Test
  fun report() = runTest {
    val base = mapOf("User-Agent" to VideoHttp.UA_CHROME, "Referer" to "https://www.bilibili.com/")
    val lines = ArrayList<String>()
    lines += probe("as-is", view, base)
    lines += probe("accept", view, base + mapOf("Accept" to "application/json, text/plain, */*", "Accept-Language" to "zh-CN,zh;q=0.9"))
    val spi = VideoHttp.request("https://api.bilibili.com/x/frontend/finger/spi", headers = base)
    lines += "spi -> ${spi.status} ${spi.body.take(200)}"
    val b3 = Regex("\"b_3\":\"([^\"]+)\"").find(spi.body)?.groupValues?.get(1)
    val b4 = Regex("\"b_4\":\"([^\"]+)\"").find(spi.body)?.groupValues?.get(1)
    if (b3 != null && b4 != null) {
      lines += probe("with-buvid", view, base + mapOf("Cookie" to "buvid3=$b3; buvid4=$b4"))
    }
    lines += probe("nav", "https://api.bilibili.com/x/web-interface/nav", base)
    fail("PROBE\n" + lines.joinToString("\n"))
  }
}
