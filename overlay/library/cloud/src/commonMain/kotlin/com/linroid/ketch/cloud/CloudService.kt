package com.linroid.ketch.cloud

import com.linroid.ketch.cloud.compat.util.Context
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.Pan115Constants as Pan115
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.StarSeaApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.XunleiKouling
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.repository.GuangYaResolveRepository
import com.yunx.app.data.repository.ILanzouAccountRepository
import com.yunx.app.data.repository.ILanzouResolveRepository
import com.yunx.app.data.repository.LanzouAccountRepository
import com.yunx.app.data.repository.LanzouResolveRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan115ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import kotlin.coroutines.cancellation.CancellationException

/** A cloud drive the service can open shares from. */
class CloudPlatform internal constructor(
  val id: String,
  val name: String,
  /** Page to sign in on in a web view, or null when the platform needs no cookie login. */
  val loginUrl: String?,
  /** Hosts whose cookies make up the login; the web view reads them for this platform. */
  val cookieHosts: List<String>,
  /** Cookie names that must all be present for the login to count. */
  val requiredCookies: List<String>,
  internal val platform: SharePlatform,
)

class CloudEntry internal constructor(
  val name: String,
  val size: Long,
  val isDirectory: Boolean,
  internal val file: ShareFile,
)

/** An opened share: its root listing plus what is needed to browse and resolve further. */
class CloudShare internal constructor(
  val platform: CloudPlatform,
  val title: String,
  val entries: List<CloudEntry>,
  internal val session: ShareSession,
  internal val rootId: String,
)

/** A resolved download: the URL plus the request headers the file host demands. */
class CloudLink(
  val url: String,
  val fileName: String,
  val size: Long,
  val headers: Map<String, String>,
)

/**
 * The entry point the iOS app talks to: open a share link, browse it, and turn a file into a
 * direct download link with the headers Ketch must send. Built from the ported YunX repositories;
 * every platform goes through the same [ShareResolveRepository] contract.
 */
class CloudService(context: Context = Context.default) {
  private val db = AppDatabase.get(context)

  private val quarkApi = QuarkApi()
  private val ucApi = UCApi()
  private val xunleiApi = XunleiApi()
  private val baiduApi = BaiduApi()
  private val c139Api = C139Api()
  private val pan123Api = Pan123Api()
  private val pan115Api = Pan115Api()
  private val guangyaApi = GuangYaApi()
  private val ilanzouApi = ILanzouApi()
  private val lanzouApi = LanzouApi()

  private val quarkAccounts = QuarkAccountRepository(db.quarkAccountDao(), quarkApi)
  private val ucAccounts = UCAccountRepository(db.ucAccountDao(), ucApi)
  private val xunleiAccounts = XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
  private val baiduAccounts = BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
  private val c139Accounts = C139AccountRepository(db.c139AccountDao())
  private val pan123Accounts = Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
  private val pan115Accounts = Pan115AccountRepository(db.pan115AccountDao(), pan115Api)
  private val guangyaAccounts = GuangYaAccountRepository(db.guangyaAccountDao(), guangyaApi)
  private val ilanzouAccounts = ILanzouAccountRepository(db.ilanzouAccountDao(), ilanzouApi)
  private val lanzouAccounts = LanzouAccountRepository(db.lanzouAccountDao(), lanzouApi)

  init {
    guangyaApi.deviceIdProvider = { guangyaAccounts.cachedDeviceId() }
  }

  private val xunleiResolver = XunleiResolveRepository(
    api = xunleiApi,
    accountProvider = { xunleiAccounts.getAccount()?.accessToken },
    deviceIdProvider = { xunleiAccounts.getAccount()?.deviceId },
    captchaProvider = { xunleiAccounts.getAccount()?.captchaToken },
    refreshProvider = {
      val acc = xunleiAccounts.getAccount()
      if (acc == null || acc.refreshToken.isBlank()) {
        null
      } else {
        xunleiApi.refreshToken(acc.refreshToken, acc.deviceId, acc.authType)?.also { (at, nrt) ->
          xunleiAccounts.updateTokens(at, nrt)
        }
      }
    },
    accountByTokenProvider = { token -> xunleiAccounts.getAccounts().firstOrNull { it.accessToken == token } },
  )

  private val resolvers: Map<SharePlatform, ShareResolveRepository> = mapOf(
    SharePlatform.QUARK to QuarkResolveRepository(quarkApi),
    SharePlatform.UC to UCResolveRepository(ucApi),
    SharePlatform.XUNLEI to xunleiResolver,
    SharePlatform.BAIDU to BaiduResolveRepository(baiduApi),
    SharePlatform.C139 to C139ResolveRepository(c139Api),
    SharePlatform.PAN123 to Pan123ResolveRepository(
      api = pan123Api,
      tokenProvider = { pan123Accounts.getAccount()?.accessToken },
    ),
    SharePlatform.PAN115 to Pan115ResolveRepository(pan115Api),
    SharePlatform.GUANGYA to GuangYaResolveRepository(
      api = guangyaApi,
      tokenProvider = { guangyaAccounts.ensureAccessToken() },
    ),
    SharePlatform.ILANZOU to ILanzouResolveRepository(ilanzouApi, ilanzouAccounts),
    SharePlatform.LANZOU to LanzouResolveRepository(lanzouApi),
  )

  private val platformList: List<CloudPlatform> = listOf(
    CloudPlatform("quark", "夸克网盘", "https://pan.quark.cn/", listOf("quark.cn"), listOf("__pus", "__puus"), SharePlatform.QUARK),
    CloudPlatform("uc", "UC 网盘", "https://drive.uc.cn/", listOf("uc.cn"), listOf("__pus", "__puus"), SharePlatform.UC),
    CloudPlatform("baidu", "百度网盘", "https://pan.baidu.com/", listOf("baidu.com"), listOf("BDUSS"), SharePlatform.BAIDU),
    CloudPlatform("pan115", "115网盘", "https://115.com/", listOf("115.com"), listOf("UID", "SEID"), SharePlatform.PAN115),
    CloudPlatform("c139", "139 网盘", "https://yun.139.com/", listOf("139.com", "10086.cn"), emptyList(), SharePlatform.C139),
    CloudPlatform("lanzou", "蓝奏云", null, emptyList(), emptyList(), SharePlatform.LANZOU),
    CloudPlatform("xunlei", "迅雷网盘", null, emptyList(), emptyList(), SharePlatform.XUNLEI),
    CloudPlatform("pan123", "123云盘", null, emptyList(), emptyList(), SharePlatform.PAN123),
    CloudPlatform("guangya", "光鸭云盘", null, emptyList(), emptyList(), SharePlatform.GUANGYA),
    CloudPlatform("ilanzou", "蓝奏云优享版", null, emptyList(), emptyList(), SharePlatform.ILANZOU),
  )

  fun platforms(): List<CloudPlatform> = platformList

  private fun platformFor(platform: SharePlatform): CloudPlatform =
    platformList.first { it.platform == platform }

  /** The stored login for [platform]: a cookie, or an access token for 123 / Xunlei. */
  private suspend fun credentialFor(platform: SharePlatform): String? = when (platform) {
    SharePlatform.UC -> ucAccounts.getAccount()?.cookie
    SharePlatform.XUNLEI -> xunleiAccounts.getAccount()?.accessToken
    SharePlatform.BAIDU -> baiduAccounts.getAccount()?.cookie
    SharePlatform.C139 -> c139Accounts.getAccount()?.cookie
    SharePlatform.PAN123 -> pan123Accounts.getAccount()?.accessToken
    SharePlatform.PAN115 -> pan115Accounts.getAccount()?.cookie
    SharePlatform.GUANGYA -> guangyaAccounts.ensureAccessToken()
    SharePlatform.ILANZOU -> ilanzouAccounts.getAccount()?.appToken
    SharePlatform.LANZOU -> lanzouAccounts.getAccount()?.cookie
    else -> quarkAccounts.getAccount()?.cookie
  }

  suspend fun isLoggedIn(platformId: String): Boolean {
    val platform = platformList.firstOrNull { it.id == platformId }?.platform ?: return false
    return !credentialFor(platform).isNullOrBlank()
  }

  /** Stores the cookie read from the sign-in web view; returns whether the platform accepted it. */
  @Throws(Exception::class, CancellationException::class)
  suspend fun saveCookie(platformId: String, cookie: String): Boolean = when (platformId) {
    "quark" -> quarkAccounts.saveQuarkAccount(cookie)
    "uc" -> ucAccounts.saveUCAccount(cookie)
    "baidu" -> baiduAccounts.saveBaiduAccount(cookie)
    "pan115" -> pan115Accounts.saveCookie(cookie)
    "c139" -> c139Accounts.saveC139Account(cookie)
    "lanzou" -> lanzouAccounts.saveCookie(cookie)
    else -> false
  }

  suspend fun logout(platformId: String) {
    val db = db
    when (platformId) {
      "quark" -> db.quarkAccountDao().clear()
      "uc" -> db.ucAccountDao().clear()
      "baidu" -> db.baiduAccountDao().clear()
      "pan115" -> db.pan115AccountDao().clear()
      "c139" -> db.c139AccountDao().clear()
      "lanzou" -> db.lanzouAccountDao().clear()
      "xunlei" -> db.xunleiAccountDao().clear()
      "pan123" -> db.pan123AccountDao().clear()
      "guangya" -> db.guangyaAccountDao().clear()
      "ilanzou" -> db.ilanzouAccountDao().clear()
    }
  }

  private fun rootIdOf(platform: SharePlatform): String = when (platform) {
    SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
    SharePlatform.XUNLEI, SharePlatform.C139, SharePlatform.PAN123 -> "0"
    SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
    SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.ILANZOU, SharePlatform.LANZOU -> ""
    else -> QuarkConstants.DEFAULT_PDIR_FID
  }

  private fun entry(file: ShareFile) = CloudEntry(file.fname, file.fsize, file.isdir, file)

  /**
   * Opens a share from pasted text: a link, a whole share message, or a Xunlei / Quark / UC
   * command. Throws with a readable message when the text is not a supported share.
   */
  @Throws(Exception::class, CancellationException::class)
  suspend fun open(text: String, password: String? = null): CloudShare {
    var link = text
    if (ShareLinkParser.parse(link) == null) {
      if (XunleiKouling.looksLikeKouling(text)) {
        xunleiResolver.resolveKouling(XunleiKouling.normalize(text)).onSuccess { link = it }
      }
      if (ShareLinkParser.parse(link) == null && StarSeaApi.looksLikeCommand(text)) {
        StarSeaApi.resolveToShareLink(text).onSuccess { link = it }
      }
    }
    val parsed = ShareLinkParser.parse(link) ?: throw IllegalArgumentException("无法识别分享链接")
    val repo = resolvers[parsed.platform]
      ?: throw IllegalArgumentException("暂不支持 ${parsed.platform.displayName}")
    val credential = credentialFor(parsed.platform).orEmpty()
    val session = repo.createSession(link, password ?: parsed.pwd, credential).getOrThrow()
    val root = rootIdOf(parsed.platform)
    val files = repo.listFiles(session, root, credential).getOrThrow()
    return CloudShare(platformFor(parsed.platform), session.title, files.map(::entry), session, root)
  }

  @Throws(Exception::class, CancellationException::class)
  suspend fun list(share: CloudShare, directory: CloudEntry): List<CloudEntry> {
    val repo = resolvers.getValue(share.platform.platform)
    val credential = credentialFor(share.platform.platform).orEmpty()
    return repo.listFiles(share.session, directory.file.fid, credential).getOrThrow().map(::entry)
  }

  /** Turns a file into a direct link plus the headers (cookie, referer, user agent) it needs. */
  @Throws(Exception::class, CancellationException::class)
  suspend fun resolve(share: CloudShare, file: CloudEntry): CloudLink {
    val platform = share.platform.platform
    val repo = resolvers.getValue(platform)
    val credential = credentialFor(platform).orEmpty()
    if (credential.isBlank() && platform != SharePlatform.LANZOU) {
      throw IllegalStateException("下载需要先登录${share.platform.name}")
    }
    val link: DownloadLink = if (platform == SharePlatform.QUARK) {
      repo.getShareDownloadLinkWithoutSave(share.session, file.file, credential).getOrNull()
        ?: repo.getShareDownloadLink(share.session, file.file, credential).getOrThrow()
    } else {
      repo.getShareDownloadLink(share.session, file.file, credential).getOrThrow()
    }
    val effective = when (platform) {
      SharePlatform.QUARK -> quarkAccounts.refreshIfPrimary(credential)
      SharePlatform.UC -> ucAccounts.refreshIfPrimary(credential)
      else -> credential
    }
    return CloudLink(
      url = link.downloadUrl,
      fileName = link.filename.ifBlank { file.name },
      size = link.size,
      headers = downloadHeaders(platform, credential, effective, link),
    )
  }

  /** Request headers per platform, ported from YunX's `buildDownloadHeaders` (signed-in links only). */
  private fun downloadHeaders(
    platform: SharePlatform,
    credential: String,
    effective: String,
    link: DownloadLink,
  ): Map<String, String> = when (platform) {
    SharePlatform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA)
    SharePlatform.BAIDU -> mapOf("Cookie" to credential, "User-Agent" to BaiduConstants.UA_NETDISK)
    SharePlatform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
    SharePlatform.PAN115 -> mapOf(
      "Cookie" to Pan115.mergeCookies(effective, link.guestCookie),
      "User-Agent" to Pan115.CLIENT_UA,
      "Referer" to Pan115.DOWNLOAD_REFERER,
    )
    SharePlatform.PAN123 -> mapOf(
      "User-Agent" to Pan123Constants.WEB_UA,
      "Referer" to Pan123Constants.DOWNLOAD_REFERER,
    )
    SharePlatform.GUANGYA -> mapOf(
      "User-Agent" to GuangYaConstants.WEB_UA,
      "Referer" to GuangYaConstants.DOWNLOAD_REFERER,
    )
    SharePlatform.ILANZOU -> mapOf(
      "User-Agent" to ILanzouConstants.WEB_UA,
      "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
      "Origin" to ILanzouConstants.WEB_SITE,
    )
    SharePlatform.LANZOU -> {
      val origin = Regex("^(https?://[^/]+)").find(link.downloadUrl)?.groupValues?.get(1)
        ?: "https://pan.lanzoui.com"
      mapOf(
        "User-Agent" to LanzouConstants.WEB_UA,
        "Referer" to "$origin/",
        "Cookie" to LanzouConstants.DOWN_IP_COOKIE,
      )
    }
    SharePlatform.UC -> mapOf(
      "Cookie" to credential,
      "User-Agent" to UCConstants.USER_AGENT,
      "Referer" to UCConstants.DOWNLOAD_REFERER,
      "Origin" to UCConstants.WEB_ORIGIN,
    )
    else -> mapOf(
      "Cookie" to effective,
      "User-Agent" to QuarkConstants.API_USER_AGENT,
      "Referer" to QuarkConstants.DOWNLOAD_REFERER,
    )
  }
}
