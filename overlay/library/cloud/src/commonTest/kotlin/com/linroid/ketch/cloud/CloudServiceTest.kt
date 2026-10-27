package com.linroid.ketch.cloud

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Cloud screen does the moment it opens, run on every target (including the iOS simulator). */
class CloudServiceTest {
  @Test
  fun constructsAndListsPlatforms() {
    val service = CloudService()
    val ids = service.platforms().map { it.id }
    assertTrue("quark" in ids && "github" in ids && "bilibili" in ids)
  }

  @Test
  fun readsLoginState() = runTest {
    val service = CloudService()
    assertEquals(emptyList(), service.loggedInPlatformIds())
  }

  @Test
  fun unknownTextGivesAMessageNotACrash() = runTest {
    val opened = CloudService().openShare("hello", null)
    assertNull(opened.share)
    assertNotNull(opened.error)
  }

  @Test
  fun passwordLoginRejectsEmptyInput() = runTest {
    val result = CloudService().loginWithPassword("pan123", "", "")
    assertTrue(!result.ok)
  }

  @Test
  fun recognisesLinksInPastedText() {
    val service = CloudService()
    assertTrue(service.isSupportedLink("我用夸克网盘给你分享了「文件」 https://pan.quark.cn/s/0123456789ab 提取码：abcd"))
    assertTrue(service.isSupportedLink("https://www.bilibili.com/video/BV1xx411c7mD"))
    assertTrue(service.isSupportedLink("https://github.com/linroid/Ketch"))
    assertTrue(!service.isSupportedLink("你好世界"))
    assertTrue(!service.isSupportedLink("https://example.com/file.zip"))
  }
}
