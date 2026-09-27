package dev.sun.wechat.features.items.moments

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeApi
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.ui.WeMomentsApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 回复朋友圈（独立功能，移植 Nuke 完整版，不挂到 WeKit 朋友圈自动化框架）：
 *  - 检测朋友圈动态(SnsInfo 表 insert/update)；处理范围：刷到时处理 / 全部已加载内容
 *  - 名单模式：全部 / 白名单(仅回复选中) / 黑名单(跳过选中)，可配置联系人名单
 *  - 回复间隔(ms)、自动刷新朋友圈(固定间隔拉取并继续回复)
 *  - 用 WeAgent 模型库生成评论(系统提示词可自定义)，截断到最大评论长度
 *  - 通过 WeMomentsApi.comment() 发送(type=2 评论)
 */
object AiReplyMoments : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "AI回复朋友圈"
    override val nameRes = R.string.feature_ai_reply_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_ai_reply_moments_description

    private const val TAG = "AiReplyMoments"
    private const val RETRY_INTERVAL_MS = 30_000L

    // 处理范围
    private const val MODE_WHEN_SEEN = 0
    private const val MODE_ALL_LOADED = 1
    // 名单模式
    private const val LIST_ALL = 0
    private const val LIST_WHITELIST = 1
    private const val LIST_BLACKLIST = 2

    private const val DEFAULT_PROMPT =
        "你是微信朋友圈评论助手。根据用户的朋友圈内容，生成一句自然、贴切、有礼貌的中文评论。" +
            "只输出评论本身，不要解释，不要引号。"

    // ---- 配置 ----
    var prompt by KvStore.prefOption("ai_reply_moments_prompt", "")
    var temperature by KvStore.prefOption("ai_reply_moments_temperature", 0.7f)
    var maxTokens by KvStore.prefOption("ai_reply_moments_max_tokens", 128)
    var maxCommentLength by KvStore.prefOption("ai_reply_moments_max_comment_length", 200)
    var listMode by KvStore.prefOption("ai_reply_moments_list_mode", LIST_ALL)
    var processMode by KvStore.prefOption("ai_reply_moments_process_mode", MODE_WHEN_SEEN)
    var replyIntervalMs by KvStore.prefOption("ai_reply_moments_interval_ms", 0L)
    var autoRefresh by KvStore.prefOption("ai_reply_moments_auto_refresh", false)
    var refreshIntervalMin by KvStore.prefOption("ai_reply_moments_refresh_interval_min", 30)
    var maxAgeDays by KvStore.prefOption("ai_reply_moments_max_age_days", 30)

    private var whitelist: Set<String>
        get() = KvStore.getStringSetOrDef(KEY_WHITELIST, emptySet())
        set(v) = KvStore.putStringSet(KEY_WHITELIST, v)

    private var blacklist: Set<String>
        get() = KvStore.getStringSetOrDef(KEY_BLACKLIST, emptySet())
        set(v) = KvStore.putStringSet(KEY_BLACKLIST, v)

    private const val KEY_WHITELIST = "ai_reply_moments_whitelist"
    private const val KEY_BLACKLIST = "ai_reply_moments_blacklist"
    private const val KEY_COMMENTED = "ai_reply_moments_commented"

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()
    /** 串行化「生成+发送」，避免并发突发打爆 LLM RPM 配额并杜绝同一朋友圈并发重复评论。 */
    private val processMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastActionSentAt = 0L

    private var refreshJob: Job? = null

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        // 从持久化重载已评论记录，避免微信/模块重启后重复评论所有朋友圈
        handledSnsIds.clear()
        runCatching { handledSnsIds.addAll(KvStore.getStringSetOrDef(KEY_COMMENTED, emptySet())) }
        lastAttemptAt.clear()
        if (processMode == MODE_ALL_LOADED) scanCachedMoments()
        startRefreshJob()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        handledSnsIds.clear()
        stopRefreshJob()
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    // ---------------- 检测 ----------------

    override fun onInsert(table: String, values: ContentValues) = processSnsInfoValues(table, values)

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) = processSnsInfoValues(table, values)

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processAsync(snsInfo)
    }

    private fun scanCachedMoments() {
        scope.launch {
            runCatching {
                // 先触发微信刷新朋友圈，把服务器新帖拉进 SnsInfo 缓存，再扫描
                WeMomentsApi.refreshTimeline()
                WeMomentsApi.rawQuerySnsInfo(
                    """
                    SELECT snsId FROM SnsInfo
                    WHERE snsId != 0
                      AND (sourceType & ${WeMomentsApi.ACTIVE_SOURCE_MASK}) != 0
                      AND (sourceType & ${WeMomentsApi.AD_SOURCE_FLAG}) = 0
                    ORDER BY createTime DESC
                    """.trimIndent()
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val snsId = cursor.getLong(0)
                        WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { processAsync(it) }
                    }
                }
            }.onFailure { WeLogger.w(TAG, "scan cached moments failed", it) }
        }
    }

    private fun startRefreshJob() {
        stopRefreshJob()
        if (!autoRefresh) return
        refreshJob = scope.launch {
            while (isActive) {
                val minutes = refreshIntervalMin.coerceIn(1, 9999)
                delay(minutes * 60_000L)
                WeLogger.d(TAG, "auto refresh scanning cached moments")
                scanCachedMoments()
            }
        }
    }

    private fun stopRefreshJob() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun processAsync(snsInfo: Any) {
        scope.launch {
            runCatching { processSnsInfo(snsInfo) }
                .onFailure { WeLogger.w(TAG, "process moments failed", it) }
        }
    }

    private suspend fun processSnsInfo(snsInfo: Any) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (!matchesListMode(owner)) return
        if (WeMomentsApi.isDeleted(snsInfo)) return
        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        if (snsTableId in handledSnsIds) return
        if (!canAttempt(snsTableId)) return

        // 时效过滤：只回最近 maxAgeDays 天内的朋友圈，超过不回；0 = 不限制
        if (maxAgeDays > 0) {
            val createdSec = runCatching { WeMomentsApi.getCreateTimeSeconds(snsInfo) }.getOrDefault(0L)
            if (createdSec > 0) {
                val ageDays = (System.currentTimeMillis() / 1000L - createdSec) / 86_400L
                WeLogger.d(TAG, "moment age owner=$owner sns=$snsTableId createTime=$createdSec age=${ageDays}d limit=${maxAgeDays}d")
                if (ageDays > maxAgeDays) {
                    WeLogger.i(TAG, "skip old moment owner=$owner sns=$snsTableId age=${ageDays}d > ${maxAgeDays}d")
                    return
                }
            } else {
                WeLogger.w(TAG, "moment age unknown (createTime=0), cannot filter owner=$owner sns=$snsTableId maxAgeDays=$maxAgeDays")
            }
        }

        val content = WeMomentsApi.getContentText(snsInfo).orEmpty().trim()

        // 串行化：拿到锁前先粗筛，拿到锁后二次确认（别的协程可能刚评完）。
        processMutex.withLock {
            if (snsTableId in handledSnsIds) return@withLock

            val text = runCatching { generateComment(content) }.getOrNull()?.trim().orEmpty()
            if (text.isBlank()) {
                WeLogger.w(TAG, "generate comment empty/failed (will retry later) owner=$owner sns=$snsTableId")
                return@withLock
            }

            val result = synchronized(actionLock) {
                if (replyIntervalMs > 0L) {
                    val wait = replyIntervalMs - (System.currentTimeMillis() - lastActionSentAt)
                    if (wait > 0L) Thread.sleep(wait)
                }
                val r = WeMomentsApi.comment(snsInfo, text)
                if (r.sent) lastActionSentAt = System.currentTimeMillis()
                r
            }
            if (result.success) {
                // 评论成功才标记已处理并持久化：每条朋友圈只评论一次，跨重启也不重复
                handledSnsIds.add(snsTableId)
                persistCommented()
                WeLogger.i(TAG, "AI commented moments owner=$owner sns=$snsTableId")
            } else {
                // 失败不标记 → 下次扫描可重试（受 canAttempt 30s 窗口限制），避免永久漏回新朋友圈
                WeLogger.w(TAG, "AI comment failed (will retry) owner=$owner sns=$snsTableId msg=${result.message}")
            }
        }
    }

    private fun persistCommented() {
        runCatching { KvStore.putStringSet(KEY_COMMENTED, handledSnsIds.toSet()) }
            .onFailure { WeLogger.w(TAG, "persist commented snsIds failed", it) }
    }

    private fun matchesListMode(owner: String): Boolean = when (listMode) {
        LIST_WHITELIST -> owner in whitelist
        LIST_BLACKLIST -> owner !in blacklist
        else -> true
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    // ---------------- AI ----------------

    private suspend fun generateComment(content: String): String = withContext(Dispatchers.IO) {
        try {
            val modelId = WeAgentRepository.firstModelId() ?: return@withContext ""
            val model = WeAgentRepository.getModel(modelId) ?: return@withContext ""
            val provider = WeAgentRepository.getModelProvider(model.providerId) ?: return@withContext ""
            val client = ModelProviderManager.clientFor(provider)
            val sysPrompt = prompt.ifBlank { DEFAULT_PROMPT }
            val userContent = if (content.isBlank()) {
                "用户发了一条朋友圈（无文字内容），请生成一条合适的评论。"
            } else {
                "朋友圈内容：$content"
            }
            val messages = listOf(
                LlmMessage(LlmRole.SYSTEM, sysPrompt),
                LlmMessage(LlmRole.USER, userContent),
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
            sb.toString().trim().take(maxCommentLength.coerceIn(1, 2000))
        } catch (e: Exception) {
            WeLogger.e(TAG, "generate comment failed", e)
            ""
        }
    }

    // ---------------- 配置 UI ----------------

    @Composable
    private fun TextFieldRow(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        allowedFilter: (Char) -> Boolean = { true },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            label = { Text(label) },
            singleLine = true,
        )
    }

    private fun showConfigDialog(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            var promptInput by remember { mutableStateOf(prompt) }
            var tempInput by remember { mutableStateOf(temperature.toString()) }
            var tokensInput by remember { mutableStateOf(maxTokens.toString()) }
            var lengthInput by remember { mutableStateOf(maxCommentLength.toString()) }
            var listModeInput by remember { mutableStateOf(listMode) }
            var processModeInput by remember { mutableStateOf(processMode) }
            var intervalInput by remember { mutableStateOf(replyIntervalMs.toString()) }
            var autoRefreshInput by remember { mutableStateOf(autoRefresh) }
            var refreshIntervalInput by remember { mutableStateOf(refreshIntervalMin.toString()) }
            var maxAgeInput by remember { mutableStateOf(maxAgeDays.toString()) }

            fun openContactPicker(title: String, kind: Int) {
                // 不关掉设置框, 否则配完名单回来时其它未保存的输入会丢
                showComposeDialog(context) {
                    ContactsSelector(
                        title = title,
                        contacts = WeDatabaseApi.getContacts(),
                        initialSelectedWxIds = if (kind == LIST_WHITELIST) whitelist else blacklist,
                        onDismiss = onDismiss,
                        onConfirm = { selected ->
                            if (kind == LIST_WHITELIST) whitelist = selected else blacklist = selected
                            showToast(
                                context,
                                localizedContext.getString(R.string.aim_selected_count, selected.size),
                            )
                            onDismiss()
                        },
                    )
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_ai_reply_moments_name)) },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        TextFieldRow(promptInput, { promptInput = it }, localizedContext.getString(R.string.aim_prompt_label))
                        TextFieldRow(lengthInput, { lengthInput = it.filter(Char::isDigit) }, localizedContext.getString(R.string.aim_max_comment_length_label)) { it.isDigit() }
                        TextFieldRow(tokensInput, { tokensInput = it.filter(Char::isDigit) }, localizedContext.getString(R.string.aim_max_tokens_label)) { it.isDigit() }
                        TextFieldRow(tempInput, { tempInput = it }, localizedContext.getString(R.string.aim_temperature_label))

                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        Text(localizedContext.getString(R.string.aim_list_mode_title))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                LIST_ALL to localizedContext.getString(R.string.aim_list_mode_all),
                                LIST_WHITELIST to localizedContext.getString(R.string.aim_list_mode_whitelist),
                                LIST_BLACKLIST to localizedContext.getString(R.string.aim_list_mode_blacklist),
                            ).forEach { (mode, label) ->
                                FilterChip(selected = listModeInput == mode, onClick = { listModeInput = mode }, label = { Text(label) })
                            }
                        }
                        if (listModeInput == LIST_WHITELIST) {
                            Button(
                                onClick = { openContactPicker(localizedContext.getString(R.string.aim_configure_whitelist), LIST_WHITELIST) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(localizedContext.getString(R.string.aim_configure_whitelist) + " (${whitelist.size})")
                            }
                        } else if (listModeInput == LIST_BLACKLIST) {
                            Button(
                                onClick = { openContactPicker(localizedContext.getString(R.string.aim_configure_blacklist), LIST_BLACKLIST) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(localizedContext.getString(R.string.aim_configure_blacklist) + " (${blacklist.size})")
                            }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        Text(localizedContext.getString(R.string.aim_process_mode_title))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                MODE_WHEN_SEEN to localizedContext.getString(R.string.aim_process_mode_seen),
                                MODE_ALL_LOADED to localizedContext.getString(R.string.aim_process_mode_all_loaded),
                            ).forEach { (mode, label) ->
                                FilterChip(selected = processModeInput == mode, onClick = { processModeInput = mode }, label = { Text(label) })
                            }
                        }

                        TextFieldRow(intervalInput, { intervalInput = it.filter(Char::isDigit) }, localizedContext.getString(R.string.aim_reply_interval_label)) { it.isDigit() }

                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(localizedContext.getString(R.string.aim_auto_refresh_title), modifier = Modifier.weight(1f))
                            Switch(checked = autoRefreshInput, onCheckedChange = { autoRefreshInput = it })
                        }
                        if (autoRefreshInput) {
                            TextFieldRow(
                                refreshIntervalInput,
                                { refreshIntervalInput = it.filter(Char::isDigit) },
                                localizedContext.getString(R.string.aim_refresh_interval_label),
                            ) { it.isDigit() }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        TextFieldRow(
                            maxAgeInput,
                            { maxAgeInput = it.filter(Char::isDigit) },
                            localizedContext.getString(R.string.aim_max_age_days_label),
                        ) { it.isDigit() }
                        Text(
                            localizedContext.getString(R.string.aim_max_age_days_desc),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        prompt = promptInput.trim()
                        maxCommentLength = lengthInput.toIntOrNull()?.coerceIn(1, 2000) ?: 200
                        maxTokens = tokensInput.toIntOrNull()?.coerceIn(1, 32768) ?: 128
                        temperature = tempInput.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f
                        listMode = listModeInput.coerceIn(0, 2)
                        processMode = processModeInput.coerceIn(0, 1)
                        replyIntervalMs = intervalInput.toLongOrNull()?.coerceIn(0L, 300_000L) ?: 0L
                        autoRefresh = autoRefreshInput
                        refreshIntervalMin = refreshIntervalInput.toIntOrNull()?.coerceIn(1, 9999) ?: 30
                        maxAgeDays = maxAgeInput.toIntOrNull()?.coerceIn(0, 36500) ?: 30
                        onDismiss()
                        // 立即应用: 按新模式扫描 + 重建刷新任务
                        if (processMode == MODE_ALL_LOADED) scanCachedMoments()
                        startRefreshJob()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }
}