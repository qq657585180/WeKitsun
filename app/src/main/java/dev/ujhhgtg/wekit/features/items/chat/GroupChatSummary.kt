package dev.ujhhgtg.wekit.features.items.chat
import dev.ujhhgtg.wekit.R

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupChatReportData(
    val groupName: String,
    val totalCount: Int,
    val activeSpeakers: Int,
    val textCount: Int,
    val typeCounts: Map<String, Int>,
    val timeSlots: List<TimeSlotData>,
    val emotions: List<EmotionData>,
    val aiInsight: String?,
)

data class TimeSlotData(
    val label: String,
    val tag: String,
    val count: Int,
)

data class EmotionData(
    val name: String,
    val percent: Int,
)

object GroupChatSummary : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "群聊统计报告"
    override val nameRes = R.string.feature_group_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_chat_summary_description

    private const val GROUP_SUMMARY_MENU_ID = 777029

    private val groupSenderRegex = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = GROUP_SUMMARY_MENU_ID,
            text = "群聊统计报告",
            drawable = GroupSummaryIcon(),
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = ::isSupportedMessage,
        ) { view, _, msgInfo ->
            showGroupSummaryDialog(view, msgInfo.talker)
        },
    )

    private fun isSupportedMessage(message: MessageInfo): Boolean =
        message.talker.isGroupChatWxId

    private fun showGroupSummaryDialog(view: View, talker: String) {
        showComposeDialog(view.context) {
            GroupSummaryDialog(
                talker = talker,
                onDismiss = onDismiss,
            )
        }
    }

    @Composable
    private fun GroupSummaryDialog(
        talker: String,
        onDismiss: () -> Unit,
    ) {
        var report by remember { mutableStateOf<GroupChatReportData?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var messageCount by remember { mutableIntStateOf(500) }
        var depth by remember { mutableStateOf(2) } // 0=快速 1=标准 2=深度 3=武汉口语
        val scope = rememberCoroutineScope()

        val groupName = remember(talker) {
            runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.ui_group_analyse_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))

                    // 分析上下文条数
                    Text(
                        text = stringResource(R.string.ui_group_analyse_context_count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { messageCount = 200 }, enabled = !isLoading) {
                            Text("200条", color = if (messageCount == 200) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 500 }, enabled = !isLoading) {
                            Text("500条", color = if (messageCount == 500) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 1000 }, enabled = !isLoading) {
                            Text("1000条", color = if (messageCount == 1000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text(
                        text = stringResource(R.string.ui_group_analyse_context_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))

                    // 分析深度
                    Text(
                        text = stringResource(R.string.ui_group_analyse_deep),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                        val depthOptions = listOf(
                            R.string.ui_group_fast,
                            R.string.ui_group_normal,
                            R.string.ui_group_deep,
                        )
                        depthOptions.forEachIndexed { index, res ->
                            TextButton(
                                onClick = { depth = index },
                                enabled = !isLoading,
                            ) {
                                Text(
                                    stringResource(res),
                                    color = if (depth == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp),
                                strokeWidth = 3.dp,
                            )
                            Text("正在生成智能分析...")
                        }
                    }

                    errorMessage?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }

                    report?.let { data ->
                        ReportHero(data)
                        DonutPanel(data.typeCounts)
                        MetricStrip(data)
                        TimeBars(data.timeSlots)
                        EmotionGauges(data.emotions)
                        InsightPanel(data.aiInsight)
                        Text(
                            text = stringResource(R.string.ui_tip_ai_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (report != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val reportText = report?.let { it.aiInsight ?: it.toStatsText() }
                                if (reportText != null && WeMessageApi.sendText(talker, reportText)) {
                                    showToast("已发送报告")
                                    onDismiss()
                                } else {
                                    showToast("发送失败，请查看日志")
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.btn_reply))
                    }
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = generateReport(talker, messageCount, depth)
                                isLoading = false
                                result.fold(
                                    onSuccess = { report = it },
                                    onFailure = { errorMessage = it.message ?: "未知错误" },
                                )
                            }
                        },
                        enabled = !isLoading,
                    ) {
                        Text("生成统计报告")
                    }
                }
            },
            dismissButton = {
                if (report != null) {
                    Row {
                        TextButton(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    val result = generateReport(talker, messageCount, depth)
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { report = it },
                                        onFailure = { errorMessage = it.message ?: "未知错误" },
                                    )
                                }
                            },
                            enabled = !isLoading,
                        ) {
                            Text(stringResource(R.string.btn_re_analyse))
                        }
                        TextButton(
                            onClick = {
                                val text = report?.let { it.aiInsight ?: it.toStatsText() }
                                if (text != null) {
                                    copyToClipboard(text)
                                    showToast("已复制报告内容")
                                }
                            },
                        ) {
                            Text(stringResource(R.string.btn_copy))
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            },
        )
    }

    @Composable
    private fun SectionTitle(title: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    @Composable
    private fun ReportHero(data: GroupChatReportData) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    text = "分析报告",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = data.groupName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "今日 · ${data.totalCount} 条消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                )
            }
        }
    }

    @Composable
    private fun DonutPanel(typeCounts: Map<String, Int>) {
        val palette = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.surfaceVariant,
        )
        val ordered = listOf("文本", "图片", "语音", "视频", "表情", "文件/链接")
            .mapNotNull { key -> typeCounts[key]?.let { key to it } }
            .filter { it.second > 0 }

        SectionTitle("消息结构")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val total = ordered.sumOf { it.second }
                if (total > 0) {
                    Canvas(Modifier.matchParentSize()) {
                        val strokeWidth = size.minDimension * 0.16f
                        val arcSize = Size(
                            width = size.width - strokeWidth,
                            height = size.height - strokeWidth,
                        )
                        val topLeft = Offset(
                            x = (size.width - arcSize.width) / 2f,
                            y = (size.height - arcSize.height) / 2f,
                        )
                        var startAngle = -90f
                        ordered.forEachIndexed { index, (_, count) ->
                            val sweep = count.toFloat() / total * 360f
                            drawArc(
                                color = palette[index % palette.size],
                                startAngle = startAngle,
                                sweepAngle = sweep - 1f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                            )
                            startAngle += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = total.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "总消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                ordered.forEachIndexed { index, (key, count) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palette[index % palette.size]),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$count 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricStrip(data: GroupChatReportData) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(vertical = 12.dp),
        ) {
            MetricCell(Modifier.weight(1f), data.totalCount, "消息")
            MetricDivider()
            MetricCell(Modifier.weight(1f), data.activeSpeakers, "发言者")
            MetricDivider()
            MetricCell(Modifier.weight(1f), data.textCount, "文本")
        }
    }

    @Composable
    private fun MetricCell(
        modifier: Modifier,
        value: Int,
        label: String,
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun MetricDivider() {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }

    @Composable
    private fun TimeBars(slots: List<TimeSlotData>) {
        SectionTitle("时段活跃")
        val max = slots.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        Column {
            slots.forEach { slot ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.width(96.dp)) {
                        Text(
                            text = slot.tag,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = slot.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(slot.count.toFloat() / max)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${slot.count}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }

    @Composable
    private fun EmotionGauges(emotions: List<EmotionData>) {
        SectionTitle("情绪指数")
        Column {
            emotions.forEach { emotion ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = emotion.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(64.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(emotion.percent / 100f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${emotion.percent}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }

    @Composable
    private fun InsightPanel(aiInsight: String?) {
        SectionTitle("智能洞察")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(14.dp),
        ) {
            if (aiInsight != null) {
                Text(
                    text = aiInsight,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "未配置 AI 模型，仅显示本地统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private suspend fun generateReport(
        talker: String,
        count: Int,
        depth: Int = 2,
    ): Result<GroupChatReportData> = withContext(Dispatchers.IO) {
        runCatching {
            val membersMap = WeDatabaseApi.getGroupMembers(talker).associate { m ->
                m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
            }

            val now = System.currentTimeMillis()
            val twentyFourHoursAgo = now - 24 * 60 * 60 * 1000L
            val messagesInRange = WeDatabaseApi.getMessagesInRange(talker, twentyFourHoursAgo, now)

            val messages = if (messagesInRange.size >= count) {
                messagesInRange.takeLast(count)
            } else {
                messagesInRange
            }

            if (messages.isEmpty()) {
                throw IllegalStateException("该群聊最近没有消息，无法生成统计报告")
            }

            val groupName = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
            val reportData = buildReportData(messages, membersMap, groupName)

            // 配置了 WeAgent 模型时，用 AI 生成智能群聊洞察
            val defaultModelId = WeAgentSettings.defaultModelId()
                ?: WeAgentRepository.firstModelId()
            if (defaultModelId != null) {
                val insight = aiGenerateReport(messages, membersMap, talker, reportData.toStatsText(), depth)
                reportData.copy(aiInsight = insight)
            } else {
                reportData
            }
        }
    }

    private fun buildAnalysisPrompt(depth: Int, statsReport: String, recentLines: String): Pair<String, String> {
        val systemPrompt: String
        val userPrompt: String

        when (depth) {
            0 -> {
                // 群聊日报总结
                systemPrompt = """你是微信群定时总结助手。
读取最近一段群聊历史消息，生成一份简短群聊日报总结。
输出内容分为：
【今日话题】简要概括大家讨论了哪几件事
【重要消息】提取通知、邀约、时间、活动、任务、求助等关键信息，无关闲聊省略
【氛围小结】简单描述今天群内聊天氛围
【闲聊亮点】有意思的段子、玩笑、梗（没有就写无）

规则：
1、文字精简，手机阅读友好，不要大段长篇
2、没有重要消息如实写，不要凭空编造内容
3、输出不带复杂markdown符号
4、语气自然口语化，适合直接发到群内"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请生成日报总结。")
                }
            }
            1 -> {
                // 话题热度统计分析
                systemPrompt = """你是群聊话题热度统计分析助手。
基于提供的群聊历史聊天记录，完成热度统计分析。
输出结构：
【热门话题排行】
按讨论热度从高到低列出前3-5个话题，简单说明该话题大家讨论的内容。

【热度说明】
高热度：多人连续发言、来回讨论
中等热度：少数几个人闲聊
低热度：只有一句话、没人接话

【活跃人员】
列出本次聊天里面发言比较多、参与讨论较多的人，不需要主观评价，只做客观统计。

【风险提醒】
识别是否存在争吵、吐槽、纠纷、敏感言论、广告引流，没有则填无。

输出约束：
1、输出简洁，拒绝大段文字，适配手机弹窗查看。
2、不编造聊天记录不存在的事件。
3、不要复杂Markdown格式，排版干净。
4、结果客观，只做热度统计，不做价值评判。"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请进行话题热度统计分析。")
                }
            }
            else -> {
                // 深度分析：群聊分析引擎 5 模块
                systemPrompt = """你为微信群聊分析引擎，深度解析群聊天上下文内容
输出分为5个模块
【话题总结】梳理本轮群聊完整主题，提炼关键事件、人物、诉求。
【情绪&氛围评估】判断整体氛围：欢乐、调侃、抱怨、焦虑、正式工作、客套寒暄、争吵。标注是否有话题冲突、阴阳、尴尬冷场。
【关键信息提取】提取时间、事件、邀约、通知、任务、求助、活动、聚餐、生日、工作安排等关键有效信息。无关闲聊废话过滤。
【人物倾向】简要说明每个人发言的立场、态度。（不需要过度揣测隐私）
【回复方案】提供4套回复思路：高情商稳妥版｜轻松幽默版｜简短附和版｜理性客观版。

硬性约束：
1.禁止脑补编造聊天不存在的信息。
2.分析结果分条清晰，便于阅读。
3.当聊天信息不足时如实说明，不强行分析。
4.输出结果不使用Markdown复杂格式，适配手机弹窗阅读。"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请进行深度分析。")
                }
            }
        }

        return Pair(systemPrompt, userPrompt)
    }

    private suspend fun aiGenerateReport(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        talker: String,
        statsReport: String,
        depth: Int = 2,
    ): String {
        val modelId = WeAgentSettings.defaultModelId()
            ?: WeAgentRepository.firstModelId()
            ?: throw IllegalStateException("未配置AI模型，请先在WeAgent设置中添加模型")
        val model = WeAgentRepository.getModel(modelId)
            ?: throw IllegalStateException("未找到模型: $modelId")
        val provider = WeAgentRepository.getModelProvider(model.providerId)
            ?: throw IllegalStateException("未找到模型提供者: ${model.providerId}")
        val client = ModelProviderManager.clientFor(provider)

        // 最近聊天片段（最多 30 条）
        val recentLines = messages.takeLast(30).joinToString("\n") { msg ->
            val sender = extractSenderId(msg, membersMap)
            val text = extractTextContent(msg, membersMap)
            "$sender: $text"
        }

        val (systemPrompt, userPrompt) = buildAnalysisPrompt(depth, statsReport, recentLines)

        val messages2 = listOf(
            LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
            LlmMessage(role = LlmRole.USER, content = userPrompt),
        )

        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = messages2,
            tools = emptyList(),
            stream = true,
        )

        var reportContent = ""
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    reportContent += event.text
                }
                is LlmStreamEvent.Completed -> {
                    if (reportContent.isBlank()) {
                        reportContent = event.message.content ?: ""
                    }
                }
                is LlmStreamEvent.Failed -> {
                    throw event.error
                }
                else -> {}
            }
        }

        val trimmed = reportContent.trim()
        if (trimmed.isBlank()) {
            throw IllegalStateException("AI未生成有效的分析报告")
        }
        return trimmed
    }

    private fun buildReportData(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        groupName: String,
    ): GroupChatReportData {
        val totalCount = messages.size

        val typeCounts = mutableMapOf<String, Int>()
        val senderCounts = mutableMapOf<String, MutableList<WeMessage>>()
        val timePeriods = mutableMapOf("凌晨" to 0, "上午" to 0, "下午" to 0, "夜晚" to 0)
        var laughCount = 0
        var questionCount = 0
        var exclamationCount = 0
        var tildeCount = 0

        for (msg in messages) {
            val type = MessageType.fromCode(msg.typeCode)
            val category = categorizeMessageType(type, msg.typeCode)
            typeCounts.mergeCount(category, 1, Int::plus)

            val senderId = extractSenderId(msg, membersMap)
            senderCounts.getOrPut(senderId) { mutableListOf() }.add(msg)

            val hour = (msg.createTime / 1000) % 86400 / 3600
            val period = when {
                hour < 6 -> "凌晨"
                hour < 12 -> "上午"
                hour < 18 -> "下午"
                else -> "夜晚"
            }
            timePeriods.mergeCount(period, 1, Int::plus)

            if (type?.isText == true) {
                val textContent = extractTextContent(msg, membersMap)
                if (textContent.contains(Regex("[哈哈呵呵嘿嘿😂🤣]"))) laughCount++
                if (textContent.endsWith("?") || textContent.endsWith("？")) questionCount++
                if (textContent.endsWith("!") || textContent.endsWith("！")) exclamationCount++
                if (textContent.contains("~") || textContent.contains("～")) tildeCount++
            }
        }

        val textMsgCount = typeCounts.getOrDefault("文本", 0).coerceAtLeast(1)
        val percent = { n: Int -> (n.toDouble() / textMsgCount * 100).toInt() }

        val timeSlots = listOf(
            TimeSlotData("00:00 - 06:00", "深夜精灵", timePeriods["凌晨"] ?: 0),
            TimeSlotData("06:00 - 12:00", "晨间飞侠", timePeriods["上午"] ?: 0),
            TimeSlotData("12:00 - 18:00", "午后玩家", timePeriods["下午"] ?: 0),
            TimeSlotData("18:00 - 24:00", "夜猫子", timePeriods["夜晚"] ?: 0),
        )

        val emotions = listOf(
            EmotionData("快乐浓度", percent(laughCount)),
            EmotionData("疑问指数", percent(questionCount)),
            EmotionData("激情指数", percent(exclamationCount)),
            EmotionData("随和指数", percent(tildeCount)),
        )

        return GroupChatReportData(
            groupName = groupName,
            totalCount = totalCount,
            activeSpeakers = senderCounts.size,
            textCount = typeCounts.getOrDefault("文本", 0),
            typeCounts = typeCounts,
            timeSlots = timeSlots,
            emotions = emotions,
            aiInsight = null,
        )
    }

    private fun GroupChatReportData.toStatsText(): String = buildString {
        appendLine("群聊统计报告")
        appendLine("群名:$groupName 总消息:${totalCount}条 发言人数:${activeSpeakers}人 文本:${textCount}条")
        appendLine("消息载体 图片:${typeCounts.getOrDefault("图片", 0)}条 语音:${typeCounts.getOrDefault("语音", 0)}条 视频:${typeCounts.getOrDefault("视频", 0)}条 表情:${typeCounts.getOrDefault("表情", 0)}条")
        appendLine("活跃时段 ${timeSlots.joinToString(" ") { "${it.tag}:${it.count}条" }}")
        appendLine("情绪指数 ${emotions.joinToString(" ") { "${it.name}:${it.percent}%" }}")
    }

    private fun categorizeMessageType(type: MessageType?, rawCode: Int): String {
        if (type == null) return "其他"
        return when {
            type.isText -> "文本"
            rawCode == MessageType.IMAGE.code -> "图片"
            rawCode == MessageType.VOICE.code -> "语音"
            rawCode == MessageType.VIDEO.code || rawCode == MessageType.MICRO_VIDEO.code -> "视频"
            type.isSystem -> "系统"
            type.isSticker -> "表情"
            type.isLink || rawCode == MessageType.FILE.code -> "文件/链接"
            else -> "其他"
        }
    }

    private fun extractSenderId(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return "我"
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(1) ?: "<未知>"
    }

    private fun extractTextContent(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return msg.content
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(2) ?: msg.content
    }

    private fun <K> MutableMap<K, Int>.mergeCount(key: K, value: Int, op: (Int, Int) -> Int) {
        this[key] = op(this.getOrDefault(key, 0), value)
    }
}

private class GroupSummaryIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)