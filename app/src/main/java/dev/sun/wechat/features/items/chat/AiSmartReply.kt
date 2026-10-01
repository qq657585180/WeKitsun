package dev.sun.wechat.features.items.chat

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.items.chat_mood.ReplyConfig
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.utils.VectorPathDrawable
import dev.sun.wechat.ui.utils.ShowComposeDialogScope
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 帮我回（原「智能回复」，并入情绪分析）：
 * 快捷选择语气/关系预设 → 按预设生成多条可编辑的回复候选 → 发送。
 *  - 语气预设：智能全能/高情商/…/委婉拒绝
 *  - 关系预设：亲人/朋友/同事/恋人/长辈/弟弟妹妹/暗恋对象/暧昧对象
 *  - 参考上下文条数（默认10，取该会话最近N条）；生成备选数（默认20）
 *  AI 调用复用 WeAgent 模型库。不再作为独立开关，由情绪分析 UI 直接打开。
 */
object AiSmartReply {

    private const val TAG = "AiSmartReply"

    private fun stylePromptKey(name: String) = "asr_style_prompt_$name"

    /** 风格提示词: 优先用户改过的, 否则内置默认 */
    private fun currentPromptFor(name: String): String =
        KvStore.getStringOrDef(
            stylePromptKey(name),
            STYLES.firstOrNull { it.first == name }?.second ?: "",
        )
    var contextLimit by prefOption("ai_reply_context_limit", 10)
    var replyCount by prefOption("ai_reply_count", 20)

    /** 语气预设 name -> prompt（与 FkWeChat 一致，含关系预设） */
    val STYLES = listOf(
        "智能全能" to "分析当前对话氛围，给出最得体、自然的回复。",
        "高情商" to "说话非常有艺术，能够化解尴尬，照顾对方感受，充满智慧。",
        "轻松闲聊" to "语气随性自然，带一点点幽默感，不要官方和生硬。",
        "严谨正式" to "语气礼貌、专业、客观，适用于职场或正式商务沟通。",
        "幽默/阴阳" to "说话风趣，带点俏皮甚至一点点阴阳怪气，非常有意思。",
        "同理/安慰" to "语气非常温柔，站在对方立场思考，给予对方情感上的支撑。",
        "客气周到" to "非常有礼貌，多使用敬语，保持一定的礼貌距离。",
        "霸道/冷酷" to "言简意赅，语气带有一点压迫感和冷酷的霸总风格。",
        "可爱/萌化" to "说话活泼，多用呀、哒、呢，增加适量颜文字，非常可爱。",
        "委婉拒绝" to "礼貌地拒绝对方的要求，不让对方感到难堪，语气委婉。",
        // ===== 关系预设（帮我回按对方身份调整语气）=====
        "亲人" to "对方是亲人。语气温暖、直接、日常化，关心具体事情，亲近而不客套。",
        "朋友" to "对方是朋友。自然平等、接住话题，语气轻松，熟悉程度以聊天为准。",
        "同事" to "对方是同事。友好、清楚、简洁，就事论事，边界明确。",
        "恋人" to "对方是恋人。可亲近、简短、有生活感，称呼和撒娇程度沿用实际聊天。",
        "长辈" to "对方是长辈。尊重、清楚、亲切，用词礼貌，称呼依据聊天。",
        "弟弟妹妹" to "对方是弟弟妹妹。亲近平等、关心具体事情，不居高临下。",
        "暗恋对象" to "对方是我暗恋的人，不代表对方也喜欢我。自然表达关注，轻松而有分寸，不默认暧昧。",
        "暧昧对象" to "以轻松、有来有往的口吻交流，调侃需结合对方实际回应。",
    )

    @Composable
    private fun ShowComposeDialogScope.SettingsDialogContent(context: android.content.Context) {
        var contextInput by remember { mutableStateOf(contextLimit.toString()) }
        var countInput by remember { mutableStateOf(replyCount.toString()) }
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_context_limit), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = contextInput,
                                    onValueChange = { contextInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.smart_reply_count), style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = countInput,
                                    onValueChange = { countInput = it.filter(Char::isDigit) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    contextLimit = contextInput.toIntOrNull()?.coerceIn(1, 50) ?: 10
                    replyCount = countInput.toIntOrNull()?.coerceIn(1, 20) ?: 20
                    onDismiss()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private fun showSmartReplyDialog(activity: Activity, msgInfo: MessageInfo) {
        showComposeDialog(activity) {
            SmartReplyDialogContent(msgInfo.talker, msgMessageText(msgInfo))
        }
    }

    /** 从聊天输入栏（+ 面板）打开智能回复：针对当前会话生成，未选中具体消息。 */
    fun openSmartReply(activity: Activity, talker: String) {
        showComposeDialog(activity) {
            SmartReplyDialogContent(talker, "")
        }
    }

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ShowComposeDialogScope.SmartReplyDialogContent(talker: String, text: String) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectedStyle by remember { mutableStateOf("智能全能") }
        var stylePromptInput by remember(selectedStyle) { mutableStateOf(currentPromptFor(selectedStyle)) }
        var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun generate() {
            if (loading) return
            scope.launch {
                loading = true
                error = null
                // 保存该风格的提示词修改
                KvStore.putString(stylePromptKey(selectedStyle), stylePromptInput.trim())
                // 换一批: 先清空旧候选, 生成中显示思考态
                candidates = emptyList()
                candidates = generateCandidates(talker, text, selectedStyle, stylePromptInput.trim())
                loading = false
                if (candidates.isEmpty()) error = "生成失败，请检查模型配置"
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_ai_smart_reply_name)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // 快捷选择语气预设
                    Text(stringResource(R.string.smart_reply_style), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        STYLES.forEach { (name, _) ->
                            FilterChip(selected = selectedStyle == name, onClick = { selectedStyle = name }, label = { Text(name) })
                        }
                    }

                    // 选中风格的介绍(提示词), 可直接编辑, 生成时生效
                    OutlinedTextField(
                        value = stylePromptInput,
                        onValueChange = { stylePromptInput = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text(stringResource(R.string.asr_style_prompt_label)) },
                        minLines = 2,
                        maxLines = 4,
                    )

                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.asr_thinking))
                        }
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        candidates.isEmpty() -> Text(stringResource(R.string.smart_reply_tap_generate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> candidates.forEach { cand ->
                            var editable by remember(cand) { mutableStateOf(cand) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                OutlinedTextField(
                                    value = editable,
                                    onValueChange = { editable = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 5,
                                )
                                Button(
                                    onClick = {
                                        val finalText = editable.trim()
                                        if (finalText.isEmpty()) { showToast(context, context.getString(R.string.ama_tts_empty_v2)); return@Button }
                                        val ok = WeMessageApi.sendText(talker, finalText)
                                        showToast(context, context.getString(if (ok) R.string.ama_sent else R.string.ama_send_failed))
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) { Text(stringResource(R.string.ama_send)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { generate() },
                    enabled = !loading,
                ) {
                    Text(
                        stringResource(
                            if (candidates.isEmpty()) R.string.ama_generate
                            else R.string.asr_regenerate
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    private fun msgMessageText(msg: MessageInfo): String =
        msg.actualContent.ifBlank { msg.content }.trim()

    private fun loadRecentContext(talker: String, limit: Int): String {
        if (talker.isEmpty()) return ""
        return try {
            val now = System.currentTimeMillis()
            WeDatabaseApi.getMessagesInRange(talker, now - 7L * 86400000L, now)
                .filter { it.content.isNotBlank() }
                .takeLast(limit)
                .joinToString("\n") { msg ->
                    (if (msg.isSend != 0) "我：" else "对方：") + msg.content
                }
        } catch (e: Exception) {
            WeLogger.e(TAG, "load context failed", e); ""
        }
    }

    private suspend fun generateCandidates(talker: String, content: String, styleName: String, promptOverride: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            try {
                // 优先用情绪分析 UI 里选的回复模型；未选或已删时回退 WeAgent 默认模型
                val modelId = ReplyConfig.modelId.takeIf { it.isNotBlank() && WeAgentRepository.getModel(it) != null }
                    ?: WeAgentRepository.firstModelId()
                    ?: return@withContext emptyList()
                val model = WeAgentRepository.getModel(modelId) ?: return@withContext emptyList()
                val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext emptyList()
                val client = ModelProviderManager.clientFor(provider)

                val count = replyCount.coerceIn(1, 20)
                val stylePrompt = promptOverride?.takeIf { it.isNotBlank() }
                    ?: STYLES.firstOrNull { it.first == styleName }?.second
                    ?: "回复自然得体，像正常人一样交流。"
                val contextText = loadRecentContext(talker, contextLimit.coerceIn(1, 50))
                val systemPrompt = buildString {
                    append("你是微信聊天助手。语气要求：$stylePrompt\n")
                    append("根据对方最后一条消息，生成${count}条回复。每条单独一行，不要序号，不要多余解释。")
                    if (contextText.isNotBlank()) append("\n\n最近聊天记录参考：\n$contextText")
                }
                val messages = listOf(
                    LlmMessage(LlmRole.SYSTEM, systemPrompt),
                    LlmMessage(LlmRole.USER, content),
                )
                val request = ModelProviderManager.buildRequest(model, messages, emptyList(), stream = true)
                val sb = StringBuilder()
                client.stream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> sb.append(event.text)
                        is LlmStreamEvent.Completed -> if (sb.isEmpty()) { event.message.content?.let { sb.append(it) } }
                        is LlmStreamEvent.Failed -> throw event.error
                        else -> {}
                    }
                }
                sb.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .filter { !it.matches(Regex("^\\d+[.、．]?.*")) }.take(count)
                    .ifEmpty { listOf(sb.toString().trim()) }
            } catch (e: Exception) {
                WeLogger.e(TAG, "generate failed", e); emptyList()
            }
        }
}

private object AiSmartReplyIcon : VectorPathDrawable(
    "M19,8l-4,4h3c0,3.31 -2.69,6 -6,6c-1.01,0 -1.97,-0.25 -2.8,-0.7l-1.46,1.46C8.97,19.54 10.43,20 12,20c4.42,0 8,-3.58 8,-8h3L19,8zM6,12c0,-3.31 2.69,-6 6,-6c1.01,0 1.97,0.25 2.8,0.7l1.46,-1.46C15.03,4.46 13.57,4 12,4c-4.42,0 -8,3.58 -8,8H1l4,4l4,-4H6z"
)