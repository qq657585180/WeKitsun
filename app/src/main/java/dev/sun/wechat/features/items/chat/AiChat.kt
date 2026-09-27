package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.data.WeAgentSettings
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI 聊天（移植 Nuke 的 `ai_chat` 模块）：让 AI 对名单内收到的每条文字消息进行连续对话回复。
 *
 * 与 Nuke 对齐的行为（逆向自 `a0.G(q42, long)`）：
 *  - 只处理收到的纯文本消息；`会话名单模式` 决定白名单(只回选中)或黑名单(跳过选中)
 *  - 回复前先等 `回复延迟`；等待后与生成后各校验一次配置版本号，配置变了就丢弃这次回复
 *    （对应 Nuke 日志 "AI reply discarded because configuration changed"）
 *  - 每个会话独立维护上下文，按 `上下文轮数` 取最近 N 轮（一轮 = 一问一答，即 2N 条消息）
 *  - 群聊消息带 `[WeChat sender: 昵称]\n` 前缀，让模型知道是谁在说话
 *  - 接口/模型走 WeKit 自己的 WeAgent 模型库（Nuke 是自带的 Base URL + API Key）
 */
object AiChat : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "AI聊天"
    override val nameRes = R.string.feature_ai_chat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_chat_description

    private const val TAG = "AiChat"

    private const val MAX_CONTEXT_ROUNDS = 20
    private const val MAX_OUTPUT_TOKENS = 32768
    private const val MAX_REPLY_DELAY_MS = 60_000L

    private const val LIST_WHITELIST = 0
    private const val LIST_BLACKLIST = 1

    // ---- 配置（键名与 Nuke 的 AIChatConfig 字段一一对应） ----
    var systemPrompt by KvStore.prefOption("ai_chat_system_prompt", "")
    var temperature by KvStore.prefOption("ai_chat_temperature", 0.7f)
    var maxTokens by KvStore.prefOption("ai_chat_max_tokens", 512)
    var contextRounds by KvStore.prefOption("ai_chat_context_rounds", 6)
    var replyDelayMs by KvStore.prefOption("ai_chat_reply_delay_ms", 0L)
    var listMode by KvStore.prefOption("ai_chat_list_mode", LIST_WHITELIST)

    private var whitelist: Set<String>
        get() = KvStore.getStringSetOrDef("ai_chat_whitelist", emptySet())
        set(value) = KvStore.putStringSet("ai_chat_whitelist", value)

    private var blacklist: Set<String>
        get() = KvStore.getStringSetOrDef("ai_chat_blacklist", emptySet())
        set(value) = KvStore.putStringSet("ai_chat_blacklist", value)

    /** 配置快照；用于「生成期间配置变了就丢弃」的判定（Nuke 用 AIChatConfig.equals）。 */
    private data class Config(
        val systemPrompt: String,
        val temperature: Float,
        val maxTokens: Int,
        val contextRounds: Int,
        val replyDelayMs: Long,
        val listMode: Int,
        val targets: Set<String>,
    )

    private fun snapshot(): Config = Config(
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens.coerceIn(1, MAX_OUTPUT_TOKENS),
        contextRounds = contextRounds.coerceIn(0, MAX_CONTEXT_ROUNDS),
        replyDelayMs = replyDelayMs.coerceIn(0L, MAX_REPLY_DELAY_MS),
        listMode = listMode,
        targets = if (listMode == LIST_BLACKLIST) blacklist else whitelist,
    )

    /** 白名单：命中才回；黑名单：命中就跳过（空名单=全部处理）。 */
    private fun Config.allowsTalker(talker: String): Boolean =
        if (listMode == LIST_WHITELIST) targets.contains(talker) else !targets.contains(talker)

    // ---- 运行时 ----
    private val configVersion = AtomicLong(0)
    private val memories = ConcurrentHashMap<String, MutableList<LlmMessage>>()
    private val handledIds = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memories.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    // ---------------- 消息入口 ----------------

    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val isSend = values.getAsInteger("isSend") ?: return
        if (isSend != 0) return
        val talker = values.getAsString("talker").orEmpty()
        if (talker.isBlank() || talker.startsWith("gh_")) return
        val content = values.getAsString("content").orEmpty()
        if (content.isBlank()) return

        // 微信同一条消息可能插入多次，按 msgSvrId/localId 去重
        val dedupeKey = values.getAsString("msgSvrId")?.takeIf { it.isNotBlank() && it != "0" }
            ?: values.getAsInteger("localId")?.toString()
        if (dedupeKey != null && handledIds.putIfAbsent(dedupeKey, true) != null) return
        pruneHandledIds()

        val config = snapshot()
        if (!config.allowsTalker(talker)) return

        val version = configVersion.get()
        scope.launch { processMessage(talker, content, config, version) }
    }

    private suspend fun processMessage(talker: String, rawContent: String, config: Config, version: Long) {
        runCatching {
            if (config.replyDelayMs > 0) delay(config.replyDelayMs.milliseconds)
            if (configVersion.get() != version) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }

            val (sender, body) = splitGroupSender(talker, rawContent)
            val userText = sender?.let { "[WeChat sender: $it]\n$body" } ?: body

            val history = memories.getOrPut(talker) { mutableListOf() }
            val messages = buildList {
                if (config.systemPrompt.isNotBlank()) add(LlmMessage(LlmRole.SYSTEM, config.systemPrompt))
                if (config.contextRounds > 0) {
                    synchronized(history) {
                        addAll(history.take(config.contextRounds * 2))
                    }
                }
                add(LlmMessage(LlmRole.USER, userText))
            }

            val reply = requestModel(config, messages)

            // 生成期间配置可能已改（换了名单/模型/轮数），此时不再发出这条回复
            if (configVersion.get() != version) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }
            val latest = snapshot()
            if (latest != config || !latest.allowsTalker(talker)) {
                WeLogger.i(TAG, "AI reply discarded because configuration changed: talker=$talker")
                return
            }
            if (reply.isFailure) {
                WeLogger.e(
                    TAG,
                    "AI completion failed: talker=$talker, model=${WeAgentRepository.firstModelId()}, reason=${reply.exceptionOrNull()?.message}",
                )
                return
            }
            val text = reply.getOrThrow().trim()
            if (text.isEmpty()) return
            if (!WeMessageApi.sendText(talker, text)) {
                WeLogger.e(TAG, "failed to send AI reply to talker=$talker")
                return
            }
            synchronized(history) {
                history += LlmMessage(LlmRole.USER, userText)
                history += LlmMessage(LlmRole.ASSISTANT, text)
                val keep = MAX_CONTEXT_ROUNDS * 2
                while (history.size > keep) history.removeAt(0)
            }
            WeLogger.i(TAG, "AI replied to talker=$talker")
        }.onFailure { WeLogger.e(TAG, "AI chat processing failed", it) }
    }

    /** 群聊 content 形如 "昵称:\n正文"，拆出发送者；单聊返回 null。 */
    private fun splitGroupSender(talker: String, content: String): Pair<String?, String> {
        if (!talker.endsWith("@chatroom")) return null to content
        val index = content.indexOf(":\n")
        if (index !in 1..64) return null to content
        return content.substring(0, index) to content.substring(index + 2)
    }

    private suspend fun requestModel(config: Config, messages: List<LlmMessage>): Result<String> = runCatching {
        val modelId = WeAgentSettings.defaultModelId() ?: WeAgentRepository.firstModelId()
            ?: error("no AI model configured")
        val model = WeAgentRepository.getModel(modelId) ?: error("model not found: $modelId")
        val provider = WeAgentRepository.getModelProvider(model.providerId)
            ?: error("model provider not found: ${model.providerId}")
        val client = ModelProviderManager.clientFor(provider)
        val base = ModelProviderManager.buildRequest(
            model = model,
            messages = messages,
            tools = emptyList(),
            stream = true,
        )
        // Nuke 的 Temperature / 最大输出 Tokens 是显式配置项：maxTokens 是 LlmRequest 字段，
        // temperature 各客户端靠 LlmJson.shallowMerge(customJsonOverride) 透传，这里合并到覆盖里。
        val override = buildJsonObject {
            base.customJsonOverride?.forEach { (key, value) -> put(key, value) }
            put("temperature", config.temperature)
        }
        val request = base.copy(maxTokens = config.maxTokens, customJsonOverride = override)
        val builder = StringBuilder()
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> builder.append(event.text)
                is LlmStreamEvent.Completed -> if (builder.isEmpty()) {
                    event.message.content?.let { builder.append(it) }
                }
                is LlmStreamEvent.Failed -> throw event.error
                else -> {}
            }
        }
        builder.toString()
    }

    private fun pruneHandledIds() {
        if (handledIds.size > 500) {
            val drop = handledIds.keys.take(handledIds.size / 2)
            drop.forEach(handledIds::remove)
        }
    }

    // ---------------- 配置界面（对齐 Nuke 的「AI 聊天设置」） ----------------

    private fun showConfigDialog(context: ComponentActivity) {
        dev.sun.wechat.ui.utils.showComposeDialog(context, directlyDismissable = false) {
            val promptInput = remember { mutableStateOf(systemPrompt) }
            val temperatureInput = remember { mutableStateOf(temperature.toString()) }
            val tokensInput = remember { mutableStateOf(maxTokens.toString()) }
            val roundsInput = remember { mutableStateOf(contextRounds.toString()) }
            val delayInput = remember { mutableStateOf(replyDelayMs.toString()) }
            val modeInput = remember { mutableStateOf(listMode) }
            val whitelistInput = remember { mutableStateOf(whitelist) }
            val blacklistInput = remember { mutableStateOf(blacklist) }
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            fun openPicker() {
                val useWhitelist = modeInput.value == LIST_WHITELIST
                val current = if (useWhitelist) whitelistInput.value else blacklistInput.value
                // 不关掉设置框, 否则配完名单回来时其它未保存的输入会丢
                dev.sun.wechat.ui.utils.showComposeDialog(context) {
                    ContactsSelector(
                        title = stringResource(
                            if (useWhitelist) R.string.ai_chat_select_whitelist_title else R.string.ai_chat_select_blacklist_title,
                        ),
                        contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups(),
                        initialSelectedWxIds = current,
                        onDismiss = onDismiss,
                    ) { selected ->
                        if (useWhitelist) whitelistInput.value = selected else blacklistInput.value = selected
                        showToast(localizedContext.getString(R.string.ai_chat_selected_count, selected.size))
                        onDismiss()
                    }
                }
            }

            AlertDialogContent(
                icon = { Icon(MaterialSymbols.Outlined.Auto_awesome, contentDescription = null) },
                title = { Text(stringResource(R.string.ai_chat_config_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        FieldRow(
                            label = stringResource(R.string.ai_chat_system_prompt),
                            value = promptInput.value,
                            onValueChange = { promptInput.value = it },
                            description = stringResource(R.string.ai_chat_system_prompt_description),
                            singleLine = false,
                        )
                        FieldRow(
                            label = stringResource(R.string.ai_chat_temperature),
                            value = temperatureInput.value,
                            onValueChange = { temperatureInput.value = it.filter { c -> c.isDigit() || c == '.' } },
                            description = stringResource(R.string.ai_chat_temperature_description),
                        )
                        FieldRow(
                            label = stringResource(R.string.ai_chat_max_tokens),
                            value = tokensInput.value,
                            onValueChange = { tokensInput.value = it.filter(Char::isDigit) },
                            description = stringResource(R.string.ai_chat_max_tokens_description),
                        )
                        FieldRow(
                            label = stringResource(R.string.ai_chat_context_rounds),
                            value = roundsInput.value,
                            onValueChange = { roundsInput.value = it.filter(Char::isDigit) },
                            description = stringResource(R.string.ai_chat_context_rounds_description),
                        )
                        FieldRow(
                            label = stringResource(R.string.ai_chat_reply_delay),
                            value = delayInput.value,
                            onValueChange = { delayInput.value = it.filter(Char::isDigit) },
                            description = stringResource(R.string.ai_chat_reply_delay_description),
                        )
                        Spacer(Modifier.height(8.dp))
                        DropDownMenuWidget(
                            iconPlaceholder = false,
                            title = stringResource(R.string.ai_chat_list_mode),
                            description = stringResource(
                                if (modeInput.value == LIST_WHITELIST) R.string.ai_chat_whitelist_description
                                else R.string.ai_chat_blacklist_description,
                            ),
                            value = modeInput.value,
                            options = listOf(
                                DropdownOption(LIST_WHITELIST, stringResource(R.string.ai_chat_list_whitelist)),
                                DropdownOption(LIST_BLACKLIST, stringResource(R.string.ai_chat_list_blacklist)),
                            ),
                            onValueChange = { modeInput.value = it },
                        )
                        BaseWidget(
                            iconPlaceholder = false,
                            title = stringResource(
                                if (modeInput.value == LIST_WHITELIST) R.string.ai_chat_configure_whitelist
                                else R.string.ai_chat_configure_blacklist,
                            ),
                            description = stringResource(
                                R.string.ai_chat_selected_count,
                                (if (modeInput.value == LIST_WHITELIST) whitelistInput.value else blacklistInput.value).size,
                            ),
                            onClick = { openPicker() },
                            trailingContent = {
                                Icon(
                                    MaterialSymbols.Outlined.Chevron_right,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val previous = snapshot()
                        systemPrompt = promptInput.value.trim()
                        temperature = temperatureInput.value.toFloatOrNull()?.coerceIn(0f, 2f) ?: temperature
                        maxTokens = tokensInput.value.toIntOrNull()?.coerceIn(1, MAX_OUTPUT_TOKENS) ?: maxTokens
                        contextRounds = roundsInput.value.toIntOrNull()?.coerceIn(0, MAX_CONTEXT_ROUNDS) ?: contextRounds
                        replyDelayMs = delayInput.value.toLongOrNull()?.coerceIn(0L, MAX_REPLY_DELAY_MS) ?: replyDelayMs
                        listMode = modeInput.value
                        whitelist = whitelistInput.value
                        blacklist = blacklistInput.value

                        val now = snapshot()
                        // 名单/轮数等变化后，旧上下文不再适用：清空并让在途回复作废
                        if (now.contextRounds != previous.contextRounds ||
                            now.listMode != previous.listMode ||
                            now.targets != previous.targets
                        ) {
                            memories.clear()
                        }
                        configVersion.incrementAndGet()
                        onDismiss()
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        description: String,
        singleLine: Boolean = true,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
