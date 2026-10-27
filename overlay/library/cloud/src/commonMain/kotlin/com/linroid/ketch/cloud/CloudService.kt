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
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.GitHubRelease
import com.yunx.app.data.network.video.HeadlessWebEngine
import com.yunx.app.data.network.video.HeadlessWebView
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseException
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.network.video.userMessage
import com.yunx.app.data.repository.VideoAccountRepository
import com.yunx.app.data.repository.VideoParseRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

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
  /** How to sign in: "cookie" (web view), "password" (account + password form), or "none". */
  val loginKind: String,
  internal val platform: SharePlatform?,
  internal val video: VideoPlatform? = null,
)

class CloudEntry internal constructor(
  val name: String,
  val size: Long,
  val isDirectory: Boolean,
  internal val file: ShareFile?,
  /** A file that already has its URL: video / image media, or a GitHub release asset. */
  internal val media: VideoMedia? = null,
  /** For a GitHub release folder: its tag. */
  internal val tag: String? = null,
)

/** An opened share: its root listing plus what is needed to browse and resolve further. */
class CloudShare internal constructor(
  val platform: CloudPlatform,
  val title: String,
  val entries: List<CloudEntry>,
  internal val session: ShareSession?,
  internal val rootId: String,
  internal val releases: List<GitHubRelease> = emptyList(),
)

/** A resolved download: the URL plus the request headers the file host demands. */
class CloudLink(
  val url: String,
  val fileName: String,
  val size: Long,
  val headers: Map<String, String>,
)

/** Outcome of [CloudService.openShare]: a share, or a message to show. */
class CloudOpened(val share: CloudShare?, val error: String?)

/** Outcome of [CloudService.listDirectory]. */
class CloudListing(val entries: List<CloudEntry>, val error: String?)

/** Outcome of a password / SMS-code sign-in. [needsCode] means a code was sent and is awaited. */
class CloudLoginResult(val ok: Boolean, val needsCode: Boolean, val message: String?)

/** Outcome of [CloudService.saveLogin]. */
class CloudSaveResult(val ok: Boolean)

/** Outcome of [CloudService.resolveFile]. */
class CloudResolved(val link: CloudLink?, val error: String?)

/** Receives the result of a [WebPageLoader] request (null when the page never became ready). */
interface WebLoadCallback {
  fun done(result: String?)
}

class WebLoadRequest(
  val url: String,
  val userAgent: String,
  val readyCheckJs: String,
  val extractJs: String,
  val timeoutMs: Long,
  val pollIntervalMs: Long,
  val documentStartJs: List<String>,
)

/** What the host app provides so video sites that render their data in a page can be read. */
interface WebPageLoader {
  fun load(request: WebLoadRequest, callback: WebLoadCallback)
}

/**
 * The entry point the iOS app talks to: open a share link, browse it, and turn a file into a
 * direct download link with the headers Ketch must send. Built from the ported YunX repositories;
 * every platform goes through the same [ShareResolveRepository] contract.
 */
class CloudService(context: Context) {
  /** The form Swift uses: the constructor above has no default argument there. */
  constructor() : this(Context.default)

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
  private val videoAccounts = VideoAccountRepository(db.videoAccountDao())
  private val videos = VideoParseRepository(videoAccounts)
  private val github = GitHubApi()

  /** The Xunlei sign-in that is waiting for the SMS code. */
  private class PendingSms(val mobile: String, val creditKey: String, val token: String)
  private var pendingXunlei: PendingSms? = null

  /** Lets the video parsers that need a rendered page use the host app's web view. */
  fun installWebLoader(loader: WebPageLoader) {
    HeadlessWebView.engine = object : HeadlessWebEngine {
      override suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long,
        pollIntervalMs: Long,
        extraDocumentStartJs: List<String>,
      ): String? = suspendCancellableCoroutine { cont ->
        loader.load(
          WebLoadRequest(url, userAgent, readyCheckJs, extractJs, timeoutMs, pollIntervalMs, extraDocumentStartJs),
          object : WebLoadCallback {
            override fun done(result: String?) {
              if (cont.isActive) cont.resume(result)
            }
          },
        )
      }
    }
  }

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
    CloudPlatform("quark", "夸克网盘", "https://pan.quark.cn/", listOf("quark.cn"), listOf("__pus", "__puus"), "cookie", SharePlatform.QUARK),
    CloudPlatform("uc", "UC 网盘", "https://drive.uc.cn/", listOf("uc.cn"), listOf("__pus", "__puus"), "cookie", SharePlatform.UC),
    CloudPlatform("baidu", "百度网盘", "https://pan.baidu.com/", listOf("baidu.com"), listOf("BDUSS"), "cookie", SharePlatform.BAIDU),
    CloudPlatform("pan115", "115网盘", "https://115.com/", listOf("115.com"), listOf("UID", "SEID"), "cookie", SharePlatform.PAN115),
    CloudPlatform("c139", "139 网盘", "https://yun.139.com/", listOf("139.com", "10086.cn"), emptyList(), "cookie", SharePlatform.C139),
    CloudPlatform("lanzou", "蓝奏云", null, emptyList(), emptyList(), "password", SharePlatform.LANZOU),
    CloudPlatform("xunlei", "迅雷网盘", null, emptyList(), emptyList(), "password", SharePlatform.XUNLEI),
    CloudPlatform("pan123", "123云盘", null, emptyList(), emptyList(), "password", SharePlatform.PAN123),
    CloudPlatform("guangya", "光鸭云盘", null, emptyList(), emptyList(), "password", SharePlatform.GUANGYA),
    CloudPlatform("ilanzou", "蓝奏云优享版", null, emptyList(), emptyList(), "password", SharePlatform.ILANZOU),
    CloudPlatform(
      "bilibili", "哔哩哔哩", VideoPlatform.BILIBILI.loginUrl, listOf("bilibili.com"), listOf("SESSDATA"),
      "cookie", null, VideoPlatform.BILIBILI,
    ),
    CloudPlatform(
      "xhs", "小红书", VideoPlatform.XHS.loginUrl, listOf("xiaohongshu.com"), listOf("web_session"),
      "cookie", null, VideoPlatform.XHS,
    ),
    CloudPlatform("github", "GitHub", null, emptyList(), emptyList(), "none", null),
  )

  fun platforms(): List<CloudPlatform> = platformList

  private fun platformFor(platform: SharePlatform): CloudPlatform =
    platformList.first { it.platform == platform }

  private fun platformById(id: String): CloudPlatform = platformList.first { it.id == id }

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
    val entry = platformList.firstOrNull { it.id == platformId } ?: return false
    entry.video?.let { return videoAccounts.activeCookie(it).isNotBlank() }
    val platform = entry.platform ?: return false
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
    "bilibili" -> videoAccounts.save(VideoPlatform.BILIBILI, cookie) != null
    "xhs" -> videoAccounts.save(VideoPlatform.XHS, cookie) != null
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
      "bilibili" -> videoAccounts.removeAll(VideoPlatform.BILIBILI)
      "xhs" -> videoAccounts.removeAll(VideoPlatform.XHS)
    }
  }

  /**
   * Account + password sign-in for the drives that have no web sign-in page. Xunlei answers a new
   * device with an SMS check: the result then has [CloudLoginResult.needsCode] set and
   * [submitCode] finishes the sign-in.
   */
  suspend fun loginWithPassword(platformId: String, account: String, password: String): CloudLoginResult = try {
    val name = account.trim()
    if (name.isEmpty() || password.isEmpty()) {
      CloudLoginResult(false, false, "请输入账号和密码")
    } else when (platformId) {
      "pan123" -> {
        val failure = pan123Accounts.loginWithPassword(name, password)
        CloudLoginResult(failure == null, false, failure)
      }
      "guangya" -> guangyaAccounts.login(name, password).toLoginResult()
      "ilanzou" -> ilanzouAccounts.login(name, password).toLoginResult()
      "lanzou" -> lanzouAccounts.login(name, password).toLoginResult()
      "xunlei" -> {
        val step = xunleiAccounts.loginWithPassword(name, password)
        when {
          step.sessionId.isNotBlank() -> {
            val ok = xunleiAccounts.finishLogin(step, name)
            CloudLoginResult(ok, false, if (ok) null else "迅雷登录失败")
          }
          step.needSms -> {
            val sms = xunleiAccounts.sendSms(name)
            val key = sms.smsCreditKey.ifBlank { step.smsCreditKey }
            val token = sms.smsToken.ifBlank { step.smsToken }
            if (key.isBlank() || token.isBlank()) {
              CloudLoginResult(false, false, sms.message.ifBlank { "验证码发送失败" })
            } else {
              pendingXunlei = PendingSms(name, key, token)
              CloudLoginResult(false, true, "验证码已发送到该手机号")
            }
          }
          else -> CloudLoginResult(false, false, step.message.ifBlank { "迅雷登录失败" })
        }
      }
      else -> CloudLoginResult(false, false, "该平台不支持账号密码登录")
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudLoginResult(false, false, e.message ?: "登录失败")
  }

  /** Finishes a sign-in that is waiting for an SMS code. */
  suspend fun submitCode(platformId: String, code: String): CloudLoginResult = try {
    val pending = pendingXunlei
    if (platformId != "xunlei" || pending == null) {
      CloudLoginResult(false, false, "没有等待验证码的登录")
    } else {
      val ok = xunleiAccounts.loginWithSms(pending.mobile, code.trim(), pending.creditKey, pending.token)
      if (ok) pendingXunlei = null
      CloudLoginResult(ok, false, if (ok) null else "验证码不正确或已过期")
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudLoginResult(false, false, e.message ?: "登录失败")
  }

  private fun <T> Result<T>.toLoginResult(): CloudLoginResult =
    CloudLoginResult(isSuccess, false, exceptionOrNull()?.message ?: if (isSuccess) null else "登录失败")

  private fun rootIdOf(platform: SharePlatform): String = when (platform) {
    SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
    SharePlatform.XUNLEI, SharePlatform.C139, SharePlatform.PAN123 -> "0"
    SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
    SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.ILANZOU, SharePlatform.LANZOU -> ""
    else -> QuarkConstants.DEFAULT_PDIR_FID
  }

  private fun entry(file: ShareFile) = CloudEntry(file.fname, file.fsize, file.isdir, file)

  private fun mediaEntry(media: VideoMedia, fallbackName: String): CloudEntry {
    val name = media.fileName.ifBlank { fallbackName }
    val label = if (media.isHls) "$name（HLS 流）" else name
    return CloudEntry(label, media.size.coerceAtLeast(0L), false, null, media.copy(fileName = name))
  }

  private suspend fun openVideo(text: String): CloudShare? {
    val link = VideoLinkParser.parse(text) ?: return null
    val result = try {
      videos.parse(link)
    } catch (e: VideoParseException) {
      throw IllegalStateException(e.userMessage())
    }
    val base = result.title.ifBlank { "video" }.take(80)
    val entries = ArrayList<CloudEntry>()
    result.medias.forEachIndexed { index, media ->
      val ext = when (media.kind) {
        VideoMediaKind.VIDEO -> if (media.isHls) ".m3u8" else ".mp4"
        VideoMediaKind.IMAGE -> ".jpg"
        VideoMediaKind.AUDIO -> ".m4a"
      }
      val suffix = if (result.medias.size > 1) "-${index + 1}" else ""
      entries += mediaEntry(media, "$base$suffix$ext")
      // Bilibili DASH keeps picture and sound in two streams; each is offered as its own file.
      if (media.audioUrl.isNotBlank()) {
        val audio = VideoMedia(
          url = media.audioUrl,
          kind = VideoMediaKind.AUDIO,
          fileName = "$base$suffix.m4a",
          headers = media.audioHeaders,
        )
        entries += mediaEntry(audio, "$base$suffix.m4a")
      }
    }
    if (entries.isEmpty()) throw IllegalStateException(result.notice.ifBlank { "没有找到可下载的内容" })
    val platform = platformList.firstOrNull { it.video == result.platform } ?: platformById("github")
    return CloudShare(platform, base, entries, null, "")
  }

  private suspend fun openGitHub(text: String): CloudShare? {
    val platform = platformById("github")
    return when (val parsed = GitHubLinkParser.parse(text)) {
      null -> null
      is GitHubLinkType.DirectFile -> {
        val media = VideoMedia(parsed.url, VideoMediaKind.VIDEO, parsed.fileName)
        CloudShare(platform, parsed.fileName, listOf(mediaEntry(media, parsed.fileName)), null, "")
      }
      is GitHubLinkType.Account -> throw IllegalArgumentException("请粘贴具体仓库的链接")
      is GitHubLinkType.Repository -> {
        val releases = github.getReleases(parsed.owner, parsed.repo)
          ?: throw IllegalStateException("读取 GitHub Release 失败（可能被限流，稍后再试）")
        val visible = releases.filter { !it.draft && it.assets.isNotEmpty() }
        if (visible.isEmpty()) throw IllegalStateException("这个仓库没有可下载的 Release 文件")
        CloudShare(
          platform,
          "${parsed.owner}/${parsed.repo}",
          visible.map {
            val title = it.name?.takeIf { n -> n.isNotBlank() } ?: it.tagName
            CloudEntry(title, 0L, true, null, null, it.tagName)
          },
          null,
          "",
          visible,
        )
      }
    }
  }

  /**
   * Opens a share from pasted text: a link, a whole share message, or a Xunlei / Quark / UC
   * command; also a video link or a GitHub repository. Throws with a readable message when the
   * text is not a supported share.
   */
  @Throws(Exception::class, CancellationException::class)
  suspend fun open(text: String, password: String? = null): CloudShare {
    openVideo(text)?.let { return it }
    var link = text
    if (ShareLinkParser.parse(link) == null) {
      if (XunleiKouling.looksLikeKouling(text)) {
        xunleiResolver.resolveKouling(XunleiKouling.normalize(text)).onSuccess { link = it }
      }
      if (ShareLinkParser.parse(link) == null && StarSeaApi.looksLikeCommand(text)) {
        StarSeaApi.resolveToShareLink(text).onSuccess { link = it }
      }
    }
    val parsed = ShareLinkParser.parse(link)
      ?: return openGitHub(text) ?: throw IllegalArgumentException("无法识别分享链接")
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
    val session = share.session
    if (session == null) {
      val release = share.releases.firstOrNull { it.tagName == directory.tag }
        ?: throw IllegalStateException("找不到这个版本")
      return release.assets.map {
        mediaEntry(VideoMedia(it.downloadUrl, VideoMediaKind.VIDEO, it.name, it.size), it.name)
      }
    }
    val platform = share.platform.platform ?: throw IllegalStateException("不是网盘分享")
    val repo = resolvers.getValue(platform)
    val credential = credentialFor(platform).orEmpty()
    val folder = directory.file ?: throw IllegalStateException("不是文件夹")
    return repo.listFiles(session, folder.fid, credential).getOrThrow().map(::entry)
  }

  /** Turns a file into a direct link plus the headers (cookie, referer, user agent) it needs. */
  @Throws(Exception::class, CancellationException::class)
  suspend fun resolve(share: CloudShare, file: CloudEntry): CloudLink {
    file.media?.let { media ->
      if (media.isHls) throw IllegalStateException("这是 HLS 分段流，Ketch 暂时无法直接下载")
      return CloudLink(media.url, media.fileName, media.size.coerceAtLeast(0L), media.headers)
    }
    val platform = share.platform.platform ?: throw IllegalStateException("无法下载这个条目")
    val session = share.session ?: throw IllegalStateException("分享已失效，请重新打开")
    val shareFile = file.file ?: throw IllegalStateException("不是文件")
    val repo = resolvers.getValue(platform)
    val credential = credentialFor(platform).orEmpty()
    if (credential.isBlank() && platform != SharePlatform.LANZOU) {
      throw IllegalStateException("下载需要先登录${share.platform.name}")
    }
    val link: DownloadLink = if (platform == SharePlatform.QUARK) {
      repo.getShareDownloadLinkWithoutSave(session, shareFile, credential).getOrNull()
        ?: repo.getShareDownloadLink(session, shareFile, credential).getOrThrow()
    } else {
      repo.getShareDownloadLink(session, shareFile, credential).getOrThrow()
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

  // Swift gets Kotlin exceptions as opaque NSErrors, so these wrappers carry the message instead.

  suspend fun openShare(text: String, password: String?): CloudOpened = try {
    CloudOpened(open(text, password), null)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudOpened(null, e.message ?: "解析失败")
  }

  suspend fun listDirectory(share: CloudShare, directory: CloudEntry): CloudListing = try {
    CloudListing(list(share, directory), null)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudListing(emptyList(), e.message ?: "读取目录失败")
  }

  suspend fun resolveFile(share: CloudShare, file: CloudEntry): CloudResolved = try {
    CloudResolved(resolve(share, file), null)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudResolved(null, e.message ?: "获取下载链接失败")
  }

  suspend fun saveLogin(platformId: String, cookie: String): CloudSaveResult = try {
    CloudSaveResult(saveCookie(platformId, cookie))
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    CloudSaveResult(false)
  }

  /**
   * Whether pasted or clipboard text holds something this service can open: a drive share link,
   * a video link or a GitHub repository. The bare-text commands (Xunlei, Quark) are left out on
   * purpose, as in YunX: any Chinese word would look like one.
   */
  fun isSupportedLink(text: String): Boolean {
    if (text.isBlank() || text.length > 2000) return false
    if (ShareLinkParser.parse(text) != null) return true
    if (VideoLinkParser.parse(text) != null) return true
    val git = GitHubLinkParser.parse(text)
    return git is GitHubLinkType.Repository
  }

  /** Ids of the platforms that currently have a stored login. */
  suspend fun loggedInPlatformIds(): List<String> = platformList.map { it.id }.filter { isLoggedIn(it) }
}
