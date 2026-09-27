package dev.sun.wechat.features.items.chat_mood

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger

/**
 * 情绪分析：文字气泡下方显示情绪 / 潜台词 / 沟通建议。
 * 对应 Yanwai 的 MessageSniffer + BubbleDecorator。走 KSP FeaturesScanner 自动注册。
 * 点击可配置 Jev/TypeSafe 渠道。
 */
object MoodFeature : ClickableFeature() {

    override val technicalId = "情绪分析"
    override val nameRes = R.string.mood_feature_name
    override val descriptionRes = R.string.mood_feature_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    override fun onEnable() {
        MoodAnalyzer.enabled = true
        MessageSniffer.ensureSubscribed()
        WeLogger.i(TAG, "情绪分析已启用")
    }

    override fun onDisable() {
        MoodAnalyzer.enabled = false
        BubbleDecorator.clearAll()
        MoodStore.clear()
        WeLogger.i(TAG, "情绪分析已停用")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context, directlyDismissable = false) {
            var showBadge by remember { mutableStateOf(MoodAnalyzer.showBadge) }
            var jevProvider by remember { mutableStateOf(MoodTransport.jevProviderId) }
            var jevKey by remember { mutableStateOf(MoodTransport.jevKey) }
            var jevEndpoint by remember { mutableStateOf(MoodTransport.jevEndpoint) }
            var jevModel by remember { mutableStateOf(MoodTransport.jevModel) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.mood_feature_name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val jevOpts = JevProvider.entries.map { DropdownOption(it.id, it.label) }
                        DropDownMenuWidget(
                            title = "Jev 渠道",
                            description = JevProvider.entries.firstOrNull { it.id == jevProvider }?.label ?: jevProvider,
                            value = jevProvider,
                            options = jevOpts,
                            enabled = true,
                            onValueChange = { jevProvider = it },
                        )
                        OutlinedTextField(
                            value = jevKey,
                            onValueChange = { jevKey = it },
                            label = { Text("Jev API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (jevProvider == "custom") {
                            OutlinedTextField(
                                value = jevEndpoint,
                                onValueChange = { jevEndpoint = it },
                                label = { Text("接口地址") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = jevModel,
                                onValueChange = { jevModel = it },
                                label = { Text("模型名") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }

                        HorizontalDivider()

                        Column {
                            Row2("显示情绪卡", showBadge) { showBadge = it }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        MoodTransport.jevProviderId = jevProvider
                        MoodTransport.jevKey = jevKey.trim()
                        MoodTransport.jevEndpoint = jevEndpoint.trim()
                        MoodTransport.jevModel = jevModel.trim()
                        MoodAnalyzer.showBadge = showBadge
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Row2(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    private const val TAG = "MoodFeature"
}
