package dev.ujhhgtg.wekit.features.items.chat_input_bar_menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Video_file
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.ClipboardUtils
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

object ParseVideo : SwitchFeature() {

    override val technicalId = "短视频解析"
    override val nameRes = R.string.feature_parse_video_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_parse_video_description

    private const val TAG = "ParseVideo"
    private const val PARSE_API = "https://apis.kit9.cn/api/aggregate_videos/api.php"

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
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    // ==================== 数据模型 ====================

    @Serializable
    private data class VideoParseResult(
        val code: Int,
        val msg: String,
        val data: VideoData?,
    )

    @Serializable
    private data class VideoData(
        val video_title: String = "",
        val video_link: String = "",
        val video_cover: String = "",
        val video_desc: String = "",
    )

    // ==================== 解析 + 下载 + 发送 ====================

    private fun parseVideo(link: String): Result<VideoParseResult> = runCatching {
        val url = PARSE_API + "?link=" + java.net.URLEncoder.encode(link, "UTF-8")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("请求失败: HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("响应为空")
            json.decodeFromString<VideoParseResult>(body)
        }
    }

    private fun downloadVideo(url: String, outPath: java.io.File): Result<java.io.File> = runCatching {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败: HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                outPath.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("下载内容为空")
        }
        if (outPath.length() < 1024) error("下载文件过小，可能无效") 
        outPath
    }

    fun showParseDialog(context: android.content.Context) {
        showComposeDialog(context, directlyDismissable = false) {
            var link by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }
            var resultInfo by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext

            // 自动读取剪贴板中的链接
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val clip = ClipboardUtils.readTextFromClipboard(appContext) ?: return@LaunchedEffect
                if (clip.contains(Regex("""(douyin|kuaishou|bilibili|ixigua|pipix|weishi|haokan|weibo|pearvideo|huya|xiaohongshu|v\.qq|quanmin\.kugou)\.?""")) ) {
                    link = clip
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

                        if (loading) {
                            Row(modifier = Modifier.fillMaxWidth()) {
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

                        resultInfo?.let { info ->
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !loading) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = link.trim()
                            if (trimmed.isEmpty()) {
                                showToast(localizedChatInputString(R.string.parse_video_link_empty))
                                return@Button
                            }
                            loading = true
                            errorMsg = null
                            resultInfo = null
                            scope.launch {
                                val parsed = withContext(Dispatchers.IO) { parseVideo(trimmed) }
                                parsed.fold(
                                    onSuccess = { result ->
                                        if (result.code != 200 || result.data?.video_link.isNullOrBlank()) {
                                            loading = false
                                            errorMsg = result.msg.ifBlank { localizedChatInputString(R.string.parse_video_api_error) }
                                            return@fold
                                        }
                                        val data = result.data!!
                                        loading = false
                                        // 下载视频并发送
                                        scope.launch {
                                            val saveResult = withContext(Dispatchers.IO) {
                                                val dir = (KnownPaths.moduleCache / "parse_video").createDirsSafe()
                                                val out = java.io.File(dir.toFile(), "video-${UUID.randomUUID()}.mp4")
                                                downloadVideo(data.video_link, out)
                                            }
                                            saveResult.fold(
                                                onSuccess = { file ->
                                                    val talker = WeCurrentConversationApi.value
                                                    if (talker.isBlank()) {
                                                        resultInfo = localizedChatInputString(R.string.parse_video_no_conversation)
                                                        return@fold
                                                    }
                                                    val sent = WeMessageApi.sendVideo(talker, file.absolutePath)
                                                    if (sent) {
                                                        showToast(localizedChatInputString(R.string.parse_video_sent))
                                                        onDismiss()
                                                    } else {
                                                        resultInfo = localizedChatInputString(R.string.parse_video_send_failed)
                                                    }
                                                },
                                                onFailure = { e ->
                                                    WeLogger.e(TAG, "download video failed", e)
                                                    errorMsg = localizedChatInputString(R.string.parse_video_download_failed, e.message.orEmpty())
                                                },
                                            )
                                        }
                                    },
                                    onFailure = { e ->
                                        loading = false
                                        WeLogger.e(TAG, "parse failed", e)
                                        errorMsg = e.message ?: localizedChatInputString(R.string.parse_video_api_error)
                                    },
                                )
                            }
                        },
                        enabled = !loading,
                    ) {
                        Text(stringResource(R.string.parse_video_parse_and_send))
                    }
                }
            )
        }
    }
}