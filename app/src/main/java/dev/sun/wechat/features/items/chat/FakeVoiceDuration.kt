package dev.sun.wechat.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.sun.wechat.R
import dev.sun.wechat.dexkit.abc.IResolveDex
import dev.sun.wechat.dexkit.dsl.dexMethod
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.android.showToast

/**
 * 伪装语音时长（完整版，对齐 Nuke 实现）：
 *  - 输入单位为「秒」(1–60)，内部 ×1000 得毫秒，clamp 到 [1, 60] 秒
 *  - hook 录音上报时长的 getLength（returnType=long），before 直接改写为伪装值
 *  - 设为 0 表示不伪装（透传真实时长）
 */
object FakeVoiceDuration : ClickableFeature(), IResolveDex {

    override val technicalId = "伪装语音时长"
    override val nameRes = R.string.feature_fake_voice_duration_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_fake_voice_duration_description

    private val methodVoiceRecorderGetLength by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.SceneVoice.Recorder", "Stop file success: ")
            }
            returnType = "long"
        }
    }
    private const val KEY_DURATION = "fake_voice_duration"

    private const val MIN_SECONDS = 1L
    private const val MAX_SECONDS = 60L

    /** 供 TTS 等发送路径复用：返回伪装毫秒数；0 表示不伪装。 */
    fun fakeDurationMs(): Long {
        val seconds = KvStore.getLongOrDef(KEY_DURATION, 0L)
        return if (seconds > 0L) seconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000L else 0L
    }

    override fun onEnable() {
        methodVoiceRecorderGetLength.hookBefore {
            // 存的是秒; 0 表示不伪装, 其余 clamp 到 [1,60] 秒 ×1000 = 毫秒
            val fake = fakeDurationMs()
            if (fake > 0L) {
                result = fake
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var secondsInput by remember { mutableStateOf(KvStore.getLongOrDef(KEY_DURATION, 0L).toString()) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_fake_voice_duration_name)) },
                text = {
                    TextField(
                        value = secondsInput,
                        onValueChange = { secondsInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.chat_fake_voice_duration_seconds)) },
                    )
                    Text(
                        stringResource(R.string.chat_fake_voice_duration_hint),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        val seconds = secondsInput.toLongOrNull()
                        if (seconds == null || seconds < 0L) {
                            showToast(localizedChatString(R.string.chat_fake_voice_duration_invalid))
                            return@Button
                        }
                        KvStore.putLong(KEY_DURATION, seconds)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
            )
        }
    }
}
