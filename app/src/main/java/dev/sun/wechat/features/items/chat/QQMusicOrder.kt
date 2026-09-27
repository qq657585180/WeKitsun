package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.chat.musicorder.QQMusicClient
import dev.sun.wechat.features.items.chat.musicorder.QQMusicSearchResult
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.DefaultColumn
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.strings.isGroupChatWxId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

object QQMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "QQ音乐点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_qq_music_order_description

    private const val TAG = "QQMusicOrder"
    private const val DEFAULT_APP_ID = "wx485a97c844086dc9"
    private const val DEFAULT_TRIGGER = "点歌"

    private var triggers by prefOption("qq_music_order_triggers", DEFAULT_TRIGGER)
    private var appId by prefOption("qq_music_order_app_id", DEFAULT_APP_ID)
    private var singerOverride by prefOption("qq_music_order_singer", "")
    private var onlyGroupChat by prefOption("qq_music_order_only_group", true)
    private var replyOnFailure by prefOption("qq_music_order_reply_on_failure", true)

    private val client = QQMusicClient()
    private val handled = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QQMusicOrder").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        handled.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        if ((values.getAsInteger("isSend") ?: return) != 0) return
        val talker = values.getAsString("talker").orEmpty()
        if (talker.isBlank() || talker.startsWith("gh_")) return
        if (onlyGroupChat && !talker.isGroupChatWxId) return

        val keyword = parseKeyword(talker, values.getAsString("content").orEmpty()) ?: return

        val dedupeKey = values.getAsString("msgSvrId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: values.getAsInteger("localId")?.toString()
        if (dedupeKey != null && handled.putIfAbsent(dedupeKey, true) != null) return
        if (handled.size > 500) handled.clear()

        scope.launch { process(talker, keyword) }
    }

    private fun parseKeyword(talker: String, raw: String): String? {
        if (raw.isBlank()) return null
        val body = if (talker.isGroupChatWxId) {
            val index = raw.indexOf(":\n")
            if (index in 1..64) raw.substring(index + 2) else raw
        } else raw
        val text = body.trim()
        val trigger = triggerWords().firstOrNull() ?: DEFAULT_TRIGGER
        if (!text.startsWith(trigger)) return null
        return text.removePrefix(trigger).trim().takeIf { it.isNotEmpty() }
    }

    private fun triggerWords(): List<String> =
        triggers.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private suspend fun process(talker: String, keyword: String) {
        val result = runCatching { client.search(keyword) }.getOrElse {
            WeLogger.e(TAG, "search failed: $keyword", it)
            QQMusicSearchResult.NotFound
        }
        val track = when (result) {
            is QQMusicSearchResult.Success -> result.track
            QQMusicSearchResult.NotFound -> {
                reply(talker, R.string.qq_music_order_not_found)
                return
            }
            QQMusicSearchResult.Unavailable -> {
                reply(talker, R.string.qq_music_order_unavailable)
                return
            }
        }
        val singer = singerOverride.trim().ifBlank { track.singer }
        val thumb = client.download(track.coverUrl)
        val sent = WeMessageApi.shareMusicVideo(
            talker = talker,
            title = track.title,
            description = singer,
            musicUrl = track.landingUrl,
            musicDataUrl = track.playUrl,
            singerName = singer,
            duration = 0,
            songLyric = track.lyric,
            thumbData = thumb,
            appId = appId.trim().ifBlank { DEFAULT_APP_ID },
        )
        if (!sent) {
            WeLogger.e(TAG, "shareMusicVideo failed: talker=$talker keyword=$keyword")
            reply(talker, R.string.qq_music_order_send_failed)
        }
    }

    private fun reply(talker: String, resId: Int) {
        if (!replyOnFailure) return
        WeMessageApi.sendText(talker, localizedChatString(resId))
    }

    private fun showConfigDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            var triggerText by remember { mutableStateOf(triggers) }
            var singerText by remember { mutableStateOf(singerOverride) }
            var appIdText by remember { mutableStateOf(appId) }
            var groupOnly by remember { mutableStateOf(onlyGroupChat) }
            var replyFail by remember { mutableStateOf(replyOnFailure) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_qq_music_order_name)) },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = triggerText,
                            onValueChange = { triggerText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_trigger_hint)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = singerText,
                            onValueChange = { singerText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_singer_hint)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = appIdText,
                            onValueChange = { appIdText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.qq_music_order_appid_hint)) },
                            singleLine = true,
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_group_only),
                            checked = groupOnly,
                            onCheckedChange = { groupOnly = it },
                        )
                        SwitchWidget(
                            title = stringResource(R.string.qq_music_order_reply_on_failure),
                            checked = replyFail,
                            onCheckedChange = { replyFail = it },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        triggers = triggerText.ifBlank { DEFAULT_TRIGGER }
                        singerOverride = singerText.trim()
                        appId = appIdText.trim().ifBlank { DEFAULT_APP_ID }
                        onlyGroupChat = groupOnly
                        replyOnFailure = replyFail
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }
}
