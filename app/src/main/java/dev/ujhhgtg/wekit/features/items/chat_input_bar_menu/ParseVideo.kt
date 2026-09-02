package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Video_file
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AndroidAudioDecoder
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.readTextFromClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

object ParseVideo : ClickableFeature() {

    override val technicalId = "短视频解析"
    override val nameRes = R.string.feature_parse_video_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_parse_video_description

    private const val TAG = "ParseVideo"

    /** 主解析线路：dy.51web.eu.org（抖音多清晰度无水印，token=dyyy）。 */
    private const val PARSE_API_PRIMARY = "https://dy.51web.eu.org/api/parse"
    private const val PARSE_API_PRIMARY_TOKEN = "dyyy"

    /** 备用解析线路：kit9 聚合解析（主线路失败时自动切换）。 */
    private const val PARSE_API = "https://apis.kit9.cn/api/aggregate_videos/api.php"
    private const val DEFAULT_BUFFER_SIZE = 8192

    private val urlRegex = Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** 抖音分享链接（v.douyin.com 短链 / www.douyin.com / iesdouyin 等）。 */
    private val douyinUrlRegex = Regex(
        """https?://(?:[\w\-.]*douyin\.com|v\.douyin\.com|iesdouyin\.com)/[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE,
    )

    private var saveDir by prefOption("parse_video_save_dir", "")
    private var autoReply by prefOption("parse_video_auto_reply", false)

    private fun defaultSaveDir(): String =
        (KnownPaths.downloads / "ParseVideo").absolutePathString()

    private fun currentSaveDir(): String =
        saveDir.ifBlank { defaultSaveDir() }

    private fun ensureSaveDir(): java.io.File =
        java.io.File(currentSaveDir()).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "parse_video",
                icon = MaterialSymbols.Outlined.Video_file,
                label = localizedChatInputString(R.string.feature_parse_video_name),
                onClick = { context, _ ->
                    showParseDialog(context)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            if (!autoReply) return@hookBefore
            val msgInfo = MessageInfo(args[0]!!)
            handleAutoReply(msgInfo)
        }
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    override fun onClick(context: androidx.activity.ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            var autoReplyChecked by remember { mutableStateOf(autoReply) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        SwitchWidget(
                            title = stringResource(R.string.parse_video_auto_reply),
                            description = stringResource(R.string.parse_video_auto_reply_description),
                            checked = autoReplyChecked,
                            onCheckedChange = {
                                autoReplyChecked = it
                                autoReply = it
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_close))
                    }
                },
            )
        }
    }

    // ==================== 数据模型 ====================

    @Serializable
    private data class VideoParseResult(
        val code: Int,
        val msg: String,
        val data: JsonElement?,
        /** 主线路返回的多清晰度列表（备用线路为空）；(label, url) 按清晰度降序。 */
        val qualityList: List<Pair<String, String>> = emptyList(),
    ) {
        /** 把 data(JsonElement) 解析成 VideoData 对象，兼容 data 为字符串(错误信息)的情况 */
        fun parsedData(): VideoData? {
            if (code != 200) return null
            val element = data ?: return null
            if (element !is kotlinx.serialization.json.JsonObject) return null
            return runCatching {
                json.decodeFromString<VideoData>(element.toString())
            }.getOrNull()
        }
    }

    @Serializable
    private data class VideoData(
        val video_id: String = "",
        val video_title: String = "",
        val video_time: Long = 0,
        val video_cover: String = "",
        val video_desc: String = "",
        val video_word: String = "",
        val video_link: String = "",
        val author: AuthorData? = null,
    )

    @Serializable
    private data class AuthorData(
        val user_id: String = "",
        val name: String = "",
        val avatar: String = "",
    )

    // ==================== 主线路（dy.51web.eu.org）数据模型 ====================

    /** 51web 解析响应：{code, msg, data:{title, video_list:[{url,level,isDisclaimer}], cover, music, images}} */
    @Serializable
    private data class PrimaryParseResult(
        val code: Int = 0,
        val msg: String = "",
        val data: PrimaryParseData? = null,
    )

    @Serializable
    private data class PrimaryParseData(
        val title: String = "",
        val cover: String = "",
        val music: String = "",
        val video_list: List<PrimaryVideoEntry> = emptyList(),
        val images: List<String> = emptyList(),
    )

    @Serializable
    private data class PrimaryVideoEntry(
        val url: String = "",
        val level: String = "",
        val isDisclaimer: Boolean = false,
    )

    // ==================== 解析 + 下载 + 发送 ====================

    private val webUserAgent =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SQ3A.220705.003) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /**
     * 抖音等平台源站做了防盗链 (Referer) 校验, 缺头会返回 403。
     * 按视频直链所在域名推导对应的 Referer; 返回 null 时不注入 (走默认)。
     */
    private fun refererFor(url: String): String? = when {
        url.contains("douyinvod") || url.contains("douyin") || url.contains("iesdouyin") ->
            "https://www.douyin.com/"
        url.contains("upos-sz") || url.contains("bilivideo") || url.contains("hdslb.com") ->
            "https://www.bilibili.com/"
        url.contains("kuaishou") || url.contains("yjsub") ->
            "https://www.kuaishou.com/"
        url.contains("ixigua") ->
            "https://www.ixigua.com/"
        url.contains("qq.com") ->
            "https://weishi.qq.com/"
        else -> null
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", webUserAgent)
        refererFor(url)?.let { builder.header("Referer", it) }
        return builder.build()
    }

    private fun parseVideo(link: String): Result<VideoParseResult> = runCatching {
        // 主线路优先：dy.51web.eu.org（多清晰度无水印）；失败/无有效地址自动回退 kit9 聚合解析
        parseByPrimary(link).getOrElse { primaryError ->
            WeLogger.w(TAG, "primary parse failed, fallback to backup: ${primaryError.message}")
            parseByBackup(link).getOrElse { backupError ->
                // 两条线路都失败：优先抛备用线路错误（其信息更通用），日志保留主线路原因
                WeLogger.e(TAG, "backup parse also failed", backupError)
                throw backupError
            }
        }
    }

    /** 主线路：dy.51web.eu.org。结果映射成统一的 VideoParseResult 供 UI 层无感消费。 */
    private fun parseByPrimary(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API_PRIMARY +
            "?token=" + java.net.URLEncoder.encode(PARSE_API_PRIMARY_TOKEN, "UTF-8") +
            "&url=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("主线路请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("主线路响应为空")
            val result = json.decodeFromString<PrimaryParseResult>(body)
            require(result.code == 200) { result.msg.ifBlank { "主线路解析失败 (code=${result.code})" } }
            val data = result.data ?: error("主线路返回数据为空")
            // 取第一个有效（非免责声明、http 开头）的视频地址；video_list 按清晰度降序排列
            val videoUrl = data.video_list
                .firstOrNull { !it.isDisclaimer && it.url.startsWith("http") }
                ?.url
                ?: error("主线路未返回可用视频地址")
            WeLogger.i(TAG, "primary parse ok, levels=${data.video_list.map { it.level }}")
            val qualities = data.video_list
                .filter { !it.isDisclaimer && it.url.startsWith("http") }
                .map { (it.level.ifBlank { "视频" }) to it.url }
            VideoParseResult(
                code = 200,
                msg = "success",
                data = json.parseToJsonElement(
                    json.encodeToString(
                        VideoData(
                            video_title = data.title,
                            video_cover = data.cover,
                            video_link = videoUrl,
                        ),
                    ),
                ),
                qualityList = qualities,
            )
        }
    }

    /** 备用线路：kit9 聚合解析（原逻辑不动）。 */
    private fun parseByBackup(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API + "?link=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("响应为空")
            json.decodeFromString<VideoParseResult>(body)
        }
    }

    /**
     * 从粘贴的分享文案中提取首个 http(s) 链接。
     * 抖音/快手等平台复制出来的是整段分享文案（含口令、表情、说明文字），
     * 直接把整段文本交给解析 API 会因无法识别链接而失败。
     */
    private fun extractVideoUrl(raw: String): String {
        return urlRegex.find(raw)?.value ?: ""
    }

    private fun downloadVideo(
        url: String,
        outPath: java.io.File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<java.io.File> = runCatching {
        val request = buildRequest(url)
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败: HTTP ${resp.code}")
            val body = resp.body ?: error("下载内容为空")
            val totalBytes = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            outPath.outputStream().use { output ->
                body.byteStream().use { input ->
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        }
        if (outPath.length() < 1024) error("下载文件过小，可能无效")
        outPath
    }

    // ==================== 群聊抖音链接自动解析回复 ====================

    /** 临时发送用目录 */
    private fun tempSendDir(context: android.content.Context): java.io.File {
        val base = context.cacheDir ?: context.filesDir
        return java.io.File(base, "parse_video_send")
    }

    private val autoReplyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打开时信号量的灯以串行化下载，避免并发重复拉取 */
    private val autoReplyMutex = Mutex()

    /** 记录最近已处理的 (talker, link 摘要) 防止重复回复 */
    private val autoReplySeen = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 当某条新消息满足条件时自动触发:
     * 群聊 + 非自己发送 + 文本含抖音分享链接 -> 解析 -> 下载到临时目录 -> 发回该群聊。
     * 由 hookBefore 在消息入库前调用, 不阻塞入库流程。
     */
    private fun handleAutoReply(msgInfo: MessageInfo) {
        try {
            if (!msgInfo.isInGroupChat) return
            if (msgInfo.type?.isText != true) return
            if (msgInfo.isSelfSender) return

            val link = extractDouyinUrl(msgInfo.humanReadableRepr)
            if (link.isEmpty()) return

            val dedupKey = "${msgInfo.talker}|$link"
            if (!autoReplySeen.add(dedupKey)) return

            val talker = msgInfo.talker
            autoReplyScope.launch {
                autoReplyMutex.withLock {
                    doAutoReply(talker, link)
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "handleAutoReply failed", e)
        }
    }

    /** 从消息文本中提取抖音分享链接；非抖音链接返回空串。 */
    private fun extractDouyinUrl(raw: String): String =
        douyinUrlRegex.find(raw)?.value ?: ""

    /** 真正执行解析 + 下载 + 发送。所有流程在地线程执行, 调用方已持锁。 */
    private suspend fun doAutoReply(talker: String, link: String) {
        val context = HostInfo.application
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val parsed = parseVideo(link).getOrElse { throw it }
                if (parsed.code != 200) {
                    error("parse failed: ${parsed.msg}")
                }
                val data = parsed.parsedData() ?: error("no video data")
                val url = data.video_link
                if (url.isBlank()) error("empty video link")

                val dir = tempSendDir(context).apply { mkdirs() }
                val out = java.io.File(dir, "auto-${UUID.randomUUID()}.mp4")
                downloadVideo(url, out).getOrElse { throw it }

                val sent = WeMessageApi.sendVideo(talker, out.absolutePath)
                if (!sent) {
                    out.delete()
                    error("sendVideo failed")
                }
            }
        }
        if (result.isFailure) {
            WeLogger.e(TAG, "auto reply failed", result.exceptionOrNull() ?: error("auto reply failed"))
        }
    }

fun showParseDialog(context: android.content.Context) {
        showComposeDialog(context, directlyDismissable = false) {
            var link by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }
            var parseResult by remember { mutableStateOf<VideoParseResult?>(null) }
            var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
            var musicFile by remember { mutableStateOf<java.io.File?>(null) }
            // 多清晰度选择：主线路返回的档位列表 + 当前选中 URL（默认第一档=最高清）
            var qualityList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var selectedQualityUrl by remember { mutableStateOf("") }
            // 主线路直出的封面/音乐地址（可一键保存）
            var directCoverUrl by remember { mutableStateOf("") }
            var directMusicUrl by remember { mutableStateOf("") }
            var savingCover by remember { mutableStateOf(false) }
            var downloadingMusic by remember { mutableStateOf(false) }
            var downloading by remember { mutableStateOf(false) }
            var downloadProgress by remember { mutableFloatStateOf(0f) }
            var sending by remember { mutableStateOf(false) }
            var pendingSendAfterParse by remember { mutableStateOf(false) }
            var extractingMusic by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext

            // 自动读取剪贴板中的链接
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val clip = readTextFromClipboard(appContext) ?: return@LaunchedEffect
                val extracted = extractVideoUrl(clip)
                if (extracted.isNotEmpty()) {
                    link = extracted
                }
            }

            fun downloadAndSend(data: VideoData) {
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    pendingSendAfterParse = false
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                if (sending) return
                sending = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        val dir = java.io.File(appContext.cacheDir ?: appContext.filesDir, "parse_video_send")
                            .apply { mkdirs() }
                        val out = java.io.File(dir, "send-${UUID.randomUUID()}.mp4")
                        downloadVideo(data.video_link, out) { downloaded, total ->
                            if (total > 0 && downloaded > 0) {
                                downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                    sending = false
                    saveResult.fold(
                        onSuccess = { file ->
                            scope.launch {
                                val sent = withContext(Dispatchers.IO) { WeMessageApi.sendVideo(talker, file.absolutePath) }
                                if (sent) {
                                    showToast(localizedChatInputString(R.string.parse_video_sent))
                                    onDismiss()
                                } else {
                                    errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                                }
                            }
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "download for send failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                        },
                    )
                }
            }

            fun doParse() {
                val trimmed = extractVideoUrl(link)
                if (trimmed.isEmpty()) {
                    showToast(localizedChatInputString(R.string.parse_video_link_empty))
                    return
                }
                loading = true
                errorMsg = null
                parseResult = null
                downloadedFile = null
                musicFile = null
                qualityList = emptyList()
                selectedQualityUrl = ""
                directCoverUrl = ""
                directMusicUrl = ""
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) { parseVideo(trimmed) }
                    loading = false
                    parsed.fold(
                        onSuccess = { r ->
                            if (r.code != 200 || r.parsedData()?.video_link.isNullOrBlank()) {
                                pendingSendAfterParse = false
                                errorMsg = r.msg.ifBlank { localizedChatInputString(R.string.parse_video_api_error) }
                                return@fold
                            }
                            parseResult = r
                            // 多清晰度与直出封面/音乐（仅主线路携带）
                            qualityList = r.qualityList
                            selectedQualityUrl = r.qualityList.firstOrNull()?.second ?: r.parsedData()!!.video_link
                            runCatching {
                                val d = json.decodeFromString<PrimaryParseData>(
                                    r.data.toString(),
                                )
                                directCoverUrl = d.cover
                                directMusicUrl = d.music
                            }
                            if (pendingSendAfterParse) {
                                pendingSendAfterParse = false
                                downloadAndSend(r.parsedData()!!)
                            }
                        },
                        onFailure = { e ->
                            pendingSendAfterParse = false
                            WeLogger.e(TAG, "parse failed", e)
                            errorMsg = e.message ?: localizedChatInputString(R.string.parse_video_api_error)
                        },
                    )
                }
            }

            fun doConvert() {
                pendingSendAfterParse = false
                doParse()
            }

            fun doSendNow() {
                val data = parseResult?.parsedData()
                if (data != null && data.video_link.isNotBlank()) {
                    downloadAndSend(data)
                } else {
                    pendingSendAfterParse = true
                    doParse()
                }
            }

            fun doDownload() {
                val data = parseResult?.parsedData() ?: return
                downloading = true
                downloadProgress = 0f
                errorMsg = null
                scope.launch {
                    val saveResult = withContext(Dispatchers.IO) {
                        val dir = ensureSaveDir()
                        val out = java.io.File(dir, "video-${UUID.randomUUID()}.mp4")
                        downloadVideo(data.video_link, out) { downloaded, total ->
                            if (total > 0 && downloaded > 0) {
                                downloadProgress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                    downloading = false
                    saveResult.fold(
                        onSuccess = { file ->
                            downloadedFile = file
                            showToast(localizedChatInputString(R.string.parse_video_downloaded))
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "download video failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                        },
                    )
                }
            }

            fun sendDownloadedVideo() {
                val file = downloadedFile ?: return
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank()) {
                    errorMsg = localizedChatInputString(R.string.parse_video_no_conversation)
                    return
                }
                scope.launch {
                    val sent = withContext(Dispatchers.IO) { WeMessageApi.sendVideo(talker, file.absolutePath) }
                    if (sent) {
                        showToast(localizedChatInputString(R.string.parse_video_sent))
                        onDismiss()
                    } else {
                        errorMsg = localizedChatInputString(R.string.parse_video_send_failed)
                    }
                }
            }

            fun copyDirectLink() {
                val data = parseResult?.parsedData() ?: return
                runCatching { copyToClipboard(appContext, data.video_link) }
                showToast(localizedChatInputString(R.string.parse_video_copied))
            }

            fun deleteDownloadedFile() {
                downloadedFile?.let { file ->
                    runCatching { file.delete() }
                }
                downloadedFile = null
                showToast(localizedChatInputString(R.string.parse_video_deleted))
            }

            fun extractMusic() {
                val data = parseResult?.parsedData() ?: return
                if (extractingMusic) return
                extractingMusic = true
                errorMsg = null
                scope.launch {
                    val result: Result<Triple<java.io.File, Int, Int>> = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = ensureSaveDir()
                            // 1. 下载视频 mp4
                            val videoFile = java.io.File(dir, "video-${UUID.randomUUID()}.mp4")
                            val dl = downloadVideo(data.video_link, videoFile).getOrElse { e -> throw e }
                            // 2. MediaExtractor 提取音轨 -> PCM
                            val pcmFile = java.io.File(dir, "audio-${UUID.randomUUID()}.pcm")
                            val decoded = AndroidAudioDecoder.decodeToPcm16(dl.absolutePath, pcmFile)
                            // 3. PCM -> MP3
                            val mp3File = java.io.File(dir, "music-${UUID.randomUUID()}.mp3")
                            val ok = AudioUtils.pcmToMp3(pcmFile.absolutePath, mp3File.absolutePath)
                            // 清理中间文件
                            pcmFile.delete()
                            if (!ok) {
                                videoFile.delete()
                                throw IllegalStateException("PCM 转 MP3 失败")
                            }
                            Triple(mp3File, decoded.sampleRate, decoded.channelCount)
                        }
                    }
                    extractingMusic = false
                    result.fold(
                        onSuccess = { (file, sampleRate, channels) ->
                            musicFile = file
                            showToast(localizedChatInputString(R.string.parse_video_music_downloaded) + ": ${"%.1f".format(file.length() / 1024.0 / 1024.0)}MB")
                        },
                        onFailure = { e ->
                            WeLogger.e(TAG, "extract music failed", e)
                            errorMsg = localizedChatInputString(R.string.parse_video_music_download_failed) + ": ${e.message.orEmpty()}"
                        },
                    )
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_parse_video_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            enabled = !loading,
                            label = { Text(stringResource(R.string.parse_video_link_hint)) },
                            placeholder = {
                                Text(stringResource(R.string.parse_video_link_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { doConvert() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_convert))
                            }
                            Button(
                                onClick = { doSendNow() },
                                modifier = Modifier.weight(1f),
                                enabled = !loading && !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_send))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (loading) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 12.dp),
                                    strokeWidth = 3.dp,
                                )
                                Text(stringResource(R.string.parse_video_loading))
                            }
                        }

                        errorMsg?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // ===== 解析结果卡片（封面 + 信息 + 清晰度 + 保存封面/音乐） =====
                        parseResult?.let { r ->
                            val data = r.parsedData() ?: return@let
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        if (directCoverUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = directCoverUrl,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(width = 108.dp, height = 144.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                                contentScale = ContentScale.Crop,
                                            )
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            if (data.video_title.isNotBlank()) {
                                                Text(
                                                    text = data.video_title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 4,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            data.author?.let { author ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = author.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (qualityList.size > 1) {
                                                Spacer(Modifier.height(8.dp))
                                                var expanded by remember { mutableStateOf(false) }
                                                val selectedLabel = qualityList
                                                    .firstOrNull { it.second == selectedQualityUrl }?.first
                                                    ?: qualityList.firstOrNull()?.first
                                                    ?: "选择清晰度"
                                                ExposedDropdownMenuBox(
                                                    expanded = expanded,
                                                    onExpandedChange = { expanded = it },
                                                ) {
                                                    OutlinedTextField(
                                                        value = selectedLabel,
                                                        onValueChange = {},
                                                        readOnly = true,
                                                        label = { Text(stringResource(R.string.parse_video_quality_label)) },
                                                        trailingIcon = {
                                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                                                        },
                                                        modifier = Modifier
                                                            .menuAnchor()
                                                            .fillMaxWidth(),
                                                    )
                                                    ExposedDropdownMenu(
                                                        expanded = expanded,
                                                        onDismissRequest = { expanded = false },
                                                    ) {
                                                        qualityList.forEach { (label, url) ->
                                                            DropdownMenuItem(
                                                                text = { Text(label) },
                                                                onClick = {
                                                                    selectedQualityUrl = url
                                                                    expanded = false
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    if (directCoverUrl.isNotBlank() || directMusicUrl.isNotBlank()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (directCoverUrl.isNotBlank()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        savingCover = true
                                                        scope.launch {
                                                            val result = withContext(Dispatchers.IO) {
                                                                runCatching {
                                                                    val dir = ensureSaveDir()
                                                                    val ext = if (directCoverUrl.contains(".png")) "png" else "jpg"
                                                                    val out = java.io.File(dir, "cover-${'$'}{UUID.randomUUID()}.$ext")
                                                                    val req = Request.Builder().url(directCoverUrl).get().build()
                                                                    httpClient.newCall(req).execute().use { resp ->
                                                                        require(resp.isSuccessful) { "HTTP ${'$'}{resp.code}" }
                                                                        resp.body.byteStream().use { ins ->
                                                                            out.outputStream().use { ins.copyTo(it) }
                                                                        }
                                                                    }
                                                                    out
                                                                }
                                                            }
                                                            savingCover = false
                                                            result.fold(
                                                                onSuccess = { file ->
                                                                    showToast(
                                                                        localizedChatInputString(R.string.parse_video_cover_saved) +
                                                                            " (${'$'}{"%.1f".format(file.length() / 1024.0))}KB)",
                                                                    )
                                                                },
                                                                onFailure = { e ->
                                                                    WeLogger.e(TAG, "save cover failed", e)
                                                                    errorMsg = e.message ?: "保存封面失败"
                                                                },
                                                            )
                                                        }
                                                    },
                                                    enabled = !savingCover,
                                                ) {
                                                    Text(
                                                        if (savingCover) stringResource(R.string.parse_video_saving_cover)
                                                        else stringResource(R.string.parse_video_save_cover),
                                                    )
                                                }
                                            }
                                            if (directMusicUrl.isNotBlank()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        downloadingMusic = true
                                                        scope.launch {
                                                            val result = withContext(Dispatchers.IO) {
                                                                runCatching {
                                                                    val dir = ensureSaveDir()
                                                                    val out = java.io.File(dir, "music-${'$'}{UUID.randomUUID()}.mp3")
                                                                    val req = buildRequest(directMusicUrl)
                                                                    httpClient.newCall(req).execute().use { resp ->
                                                                        require(resp.isSuccessful) { "HTTP ${'$'}{resp.code}" }
                                                                        resp.body.byteStream().use { ins ->
                                                                            out.outputStream().use { ins.copyTo(it) }
                                                                        }
                                                                    }
                                                                    out
                                                                }
                                                            }
                                                            downloadingMusic = false
                                                            result.fold(
                                                                onSuccess = { file ->
                                                                    showToast(
                                                                        localizedChatInputString(R.string.parse_video_music_downloaded) +
                                                                            ": ${'$'}{"%.1f".format(file.length() / 1024.0 / 1024.0)}MB",
                                                                    )
                                                                },
                                                                onFailure = { e ->
                                                                    WeLogger.e(TAG, "download music failed", e)
                                                                    errorMsg = e.message ?: "下载音乐失败"
                                                                },
                                                            )
                                                        }
                                                    },
                                                    enabled = !downloadingMusic,
                                                ) {
                                                    Text(
                                                        if (downloadingMusic) stringResource(R.string.parse_video_downloading_music)
                                                        else stringResource(R.string.parse_video_download_music_direct),
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }

                                Spacer(Modifier.height(8.dp))

                                // ===== 下载状态 =====

                                when {
                                    downloading || sending -> {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            val percent = (downloadProgress * 100).toInt()
                                            LinearProgressIndicator(
                                                progress = { downloadProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = if (sending) {
                                                    localizedChatInputString(R.string.parse_video_sending)
                                                } else if (percent >= 100) {
                                                    localizedChatInputString(R.string.parse_video_downloading)
                                                } else {
                                                    localizedChatInputString(R.string.parse_video_downloading) +
                                                        " $percent%"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    downloadedFile != null -> {
                                        Text(
                                            text = buildString {
                                                append(localizedChatInputString(R.string.parse_video_downloaded))
                                                append(" (")
                                                append("%.1f".format(downloadedFile!!.length() / 1024.0 / 1024.0))
                                                append("MB)")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !loading && !sending) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    // ===== 按钮组 =====
                    val data = parseResult?.parsedData()
                    if (data != null && downloadedFile == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { doDownload() },
                                enabled = !downloading && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_download))
                            }
                            Button(
                                onClick = { extractMusic() },
                                enabled = !extractingMusic && !sending,
                            ) {
                                Text(stringResource(R.string.parse_video_download_music))
                            }
                        }
                    } else if (data != null) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { sendDownloadedVideo() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.parse_video_send_video))
                                }
                                TextButton(
                                    onClick = { copyDirectLink() },
                                ) {
                                    Text(stringResource(R.string.parse_video_copy_link))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = { deleteDownloadedFile() },
                                ) {
                                    Text(stringResource(R.string.parse_video_delete))
                                }
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                },
            )
        }
    }
}