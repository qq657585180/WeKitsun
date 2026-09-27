package dev.sun.wechat.features.items.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.reflekt.reflekt
import dev.sun.wechat.R
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.i18n.LocalWeKitLocalizedContext
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.Button
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.ColorPickerWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.HookParam
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** View tag for the wrapper holding the avatar + time. */
private const val TAG_AVATAR_TIME_WRAPPER = "wekit_avatar_time_wrapper"
/** View tag for the time TextView under the avatar. */
private const val TAG_AVATAR_TIME_TEXT = "wekit_avatar_time_text"

/**
 * 头像下显示时间（FkWeChat 方案）：在每条消息的头像正下方显示发送时间 HH:mm。
 *
 * 实现：把微信头像从父容器移到新建的垂直 LinearLayout(wrapper) 中，再把 wrapper 放回头像
 * 原位置，最后在 wrapper 里头像下方加入一个 TextView。用 tag 缓存避免重复创建。
 */
object DisplayMessageTimeByAvatar : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "显示消息时间"
    override val nameRes = R.string.feature_display_message_time_by_avatar_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_display_message_time_by_avatar_description

    private var timePattern by prefOption("avatar_time_pattern", "HH:mm")
    private var textSize by prefOption("avatar_time_text_size", 6)
    private var textColor by prefOption("avatar_time_text_color", "#FF8AB22F")

    private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        try {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            if (msgInfo.type?.isSystem == true) return

            val tag = view.tag ?: return
            val avatar = tag.reflekt()
                .firstField {
                    name = "avatarIV"
                    superclass()
                }
                .get() as? View ?: return

            // 头像真正所在节点：若被 MaskLayout 包裹则取 MaskLayout，否则取头像自身
            val avatarHolder = if (avatar.parent?.javaClass?.name == MASK_LAYOUT_CLASS) {
                avatar.parent as? ViewGroup ?: return
            } else {
                avatar
            }

            val rowParent = avatarHolder.parent as? ViewGroup ?: return

            // 1. 复用/创建 wrapper（头像 + 下方时间 的垂直容器）
            val wrapper = ensureWrapper(rowParent, avatarHolder)

            // 2. 设置头像下方时间
            val timeText = ensureTimeText(wrapper, view)
            val parsedColor = runCatching { textColor.toColorInt() }.getOrElse { Color.GRAY }
            timeText.setTextColor(parsedColor)
            timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
            timeText.text = formatTime(msgInfo.createTime, timePattern)
            timeText.visibility = View.VISIBLE
            wrapper.visibility = View.VISIBLE
        } catch (error: Exception) {
            // 布局结构变化时静默跳过，不影响消息显示
            WeLogger.d("DisplayMessageTimeByAvatar", "skip avatar time injection", error)
        }
    }

    /** 找到头像所在的 wrapper（没有则创建：把头像移入新建 LinearLayout 并放回原位置）。 */
    private fun ensureWrapper(rowParent: ViewGroup, avatarHolder: View): LinearLayout {
        val existing = rowParent.findViewWithTag<LinearLayout>(TAG_AVATAR_TIME_WRAPPER)
        if (existing != null) return existing

        val holderIndex = rowParent.indexOfChild(avatarHolder)
        val holderLp = avatarHolder.layoutParams

        val wrapper = LinearLayout(avatarHolder.context).apply {
            tag = TAG_AVATAR_TIME_WRAPPER
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 若原 holder 有 id，转给 wrapper 并给 holder 生成新 id（避免 id 冲突/锚定错位）
        if (avatarHolder.id != View.NO_ID) {
            wrapper.id = avatarHolder.id
            avatarHolder.id = View.generateViewId()
        }

        // 把头像 holder 移入 wrapper，再放 wrapper 回原位置
        rowParent.removeView(avatarHolder)
        wrapper.addView(avatarHolder, holderLp)
        rowParent.addView(wrapper, holderIndex, holderLp)
        wrapper.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

        return wrapper
    }

    /** 在 wrapper 中找到/创建头像下方的 TextView。 */
    private fun ensureTimeText(wrapper: LinearLayout, rowView: View): TextView {
        val existing = wrapper.findViewWithTag<TextView>(TAG_AVATAR_TIME_TEXT)
        if (existing != null) return existing

        return TextView(rowView.context).apply {
            tag = TAG_AVATAR_TIME_TEXT
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 头像下方留 2dp 间距
            lp.topMargin = dp(2f)
            wrapper.addView(this, lp)
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        android.content.res.Resources.getSystem().displayMetrics,
    ).toInt()

    private fun formatTime(createTime: Long, pattern: String): String {
        // 兼容秒/毫秒单位
        val ms = if (createTime > 1000000000000L) createTime else createTime * 1000L
        return runCatching {
            SimpleDateFormat(pattern.ifBlank { "HH:mm" }, Locale.getDefault()).format(Date(ms))
        }.getOrElse { "" }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext = LocalWeKitLocalizedContext.current
            var timeFormatInput by remember { mutableStateOf(timePattern) }
            var textSizeInputRaw by remember { mutableStateOf(textSize.toString()) }
            var textColorInput by remember { mutableStateOf(textColor) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_display_message_time_by_avatar_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                OutlinedTextField(
                                    value = timeFormatInput,
                                    onValueChange = { timeFormatInput = it },
                                    label = { Text(stringResource(R.string.avatar_time_format)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = textSizeInputRaw,
                                    onValueChange = { textSizeInputRaw = it.filter { c -> c.isDigit() } },
                                    label = { Text(stringResource(R.string.avatar_time_font_size)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.avatar_time_color),
                                    value = textColorInput,
                                    onValueChange = { textColorInput = it },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val textSizeInput = textSizeInputRaw.toIntOrNull()
                        if (textSizeInput == null || textSizeInput <= 0) {
                            showToast(localizedContext.getString(R.string.avatar_time_invalid_number))
                            return@Button
                        }
                        timePattern = timeFormatInput.ifBlank { "HH:mm" }
                        textSize = textSizeInput
                        textColor = textColorInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }
}
