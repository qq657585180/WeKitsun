package dev.ujhhgtg.wekit.features.items.chat.emotion

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ChatAiEmotionAnalysis : SwitchFeature(),
    WeChatMessageViewApi.IMessageViewLifecycleListener {

    override val technicalId = "AI情绪分析"
    override val nameRes = R.string.feature_chat_emotion_analysis_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_emotion_analysis_description

    private const val TAG = "ChatEmotion"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tagEmotionLabel = "wekit_emotion_label"

    private var apiUrl by prefOption("emotion_api_url", JevChannel.TYPESAFE.url)
    private var apiKey by prefOption("emotion_api_key", "")
    private var modelName by prefOption("emotion_model_name", JevChannel.TYPESAFE.model)

    override fun onEnable() {
        WeChatMessageViewApi.addLifecycleListener(this)
        WeLogger.i(TAG, "情绪分析已启用")
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeLifecycleListener(this)
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (message.isSelfSender) return
        if (message.type?.isText != true) return

        val text = message.humanReadableRepr
        if (text.length < 2 || text.length > 500) return

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    JevEmotionClient.analyzeEmotion(
                        text = text,
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        model = modelName
                    ).getOrElse { throw it }
                }

                withContext(Dispatchers.Main) {
                    showEmotionLabel(view, result)
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "情绪分析出错: ${e.message}")
            }
        }
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo) {
        removeEmotionLabel(view)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        removeEmotionLabel(view)
    }

    private fun showEmotionLabel(view: View, result: JevEmotionClient.EmotionResult) {
        if (view.parent !is ViewGroup) return

        val parent = view.parent as ViewGroup
        val existing = parent.findViewWithTag<View>(tagEmotionLabel)
        existing?.let { parent.removeView(it) }

        val icon = getEmotionIcon(result.emotion)
        val color = getEmotionColor(result.emotion)

        val density = view.context.resources.displayMetrics.density

        val label = LinearLayout(view.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = tagEmotionLabel
            setPadding((4 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
            }
        }

        val iconView = TextView(view.context).apply {
            text = icon
            textSize = 11f
            setTextColor(color)
            gravity = Gravity.CENTER
        }
        label.addView(iconView)

        val textView = TextView(view.context).apply {
            text = "${result.emotion}"
            textSize = 11f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((4 * density).toInt(), 0, 0, 0)
        }
        label.addView(textView)

        val scoreView = TextView(view.context).apply {
            text = " ${result.score}"
            textSize = 9f
            setTextColor(Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
            gravity = Gravity.CENTER
            setPadding((2 * density).toInt(), 0, 0, 0)
        }
        label.addView(scoreView)

        val viewParams = view.layoutParams
        if (viewParams is ViewGroup.MarginLayoutParams) {
            viewParams.bottomMargin = 6
            view.layoutParams = viewParams
        }

        parent.addView(label, parent.indexOfChild(view) + 1)
    }

    private fun removeEmotionLabel(view: View) {
        if (view.parent !is ViewGroup) return
        val parent = view.parent as ViewGroup
        val existing = parent.findViewWithTag<View>(tagEmotionLabel)
        existing?.let { parent.removeView(it) }
    }

    private fun getEmotionIcon(emotion: String): String = when {
        emotion.contains("开心") -> "😊"
        emotion.contains("平静") -> "😐"
        emotion.contains("失落") -> "😔"
        emotion.contains("委屈") -> "😢"
        emotion.contains("生气") -> "😠"
        emotion.contains("缓和") -> "😌"
        else -> "🤔"
    }

    private fun getEmotionColor(emotion: String): Int = when {
        emotion.contains("开心") -> Color.parseColor("#FF6B6B")
        emotion.contains("平静") -> Color.parseColor("#4ECDC4")
        emotion.contains("失落") -> Color.parseColor("#95A5A6")
        emotion.contains("委屈") -> Color.parseColor("#A78BFA")
        emotion.contains("生气") -> Color.parseColor("#F97316")
        emotion.contains("缓和") -> Color.parseColor("#34D399")
        else -> Color.parseColor("#9CA3AF")
    }
}

enum class JevChannel(val label: String, val url: String, val model: String) {
    TYPESAFE("Jev 官方", "https://api.typesafe.ai/v1/chat/completions", "jev-1.13.0"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "typesafe/jev-1.13"),
    VERCEL("Vercel AI", "https://ai-gateway.vercel.sh/typesafe/v1/chat/completions", "typesafe-ai/jev"),
    CUSTOM("自定义", "", "")
}