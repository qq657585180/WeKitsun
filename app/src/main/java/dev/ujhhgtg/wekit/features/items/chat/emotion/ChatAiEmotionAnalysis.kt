package dev.sun.wechat.features.items.chat.emotion

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.core.SwitchFeature
import dev.sun.wechat.preferences.WePrefs.Companion.prefOption
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.IdentityHashMap

/**
 * AI 情绪分析 — 遵循言外 (Yanwai) 架构重写。
 *
 * 核心设计：
 * - MoodStore.acquire/complete/release：去重 + 先占位后填充
 * - JevEmotionProtocol：两轮有界 choice（不做自由文本生成）
 * - BubbleDecorator：找到消息气泡 (MMNeat7extView) → 挂在下方
 * - 卡片可点击重试失败，错误态允许重试
 */
object ChatAiEmotionAnalysis : SwitchFeature(),
    WeChatMessageViewApi.IMessageViewLifecycleListener {

    override val technicalId = "AI情绪分析"
    override val nameRes = R.string.feature_chat_emotion_analysis_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_emotion_analysis_description

    private const val TAG = "ChatEmotion"
    private const val TAG_CARD = "wekit_emotion_card"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cards = IdentityHashMap<View, CardState>()

    private var apiUrl by prefOption("emotion_api_url", "https://api.typesafe.ai/v1/systemone")
    private var apiKey by prefOption("emotion_api_key", "")
    private var modelName by prefOption("emotion_model_name", "jev-1.13.0")

    private data class CardState(
        val key: String,
        val view: View,
        val detach: View.OnAttachStateChangeListener,
    )

    override fun onEnable() {
        WeChatMessageViewApi.addLifecycleListener(this)
        WeLogger.i(TAG, "情绪分析已启用")
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeLifecycleListener(this)
        cards.keys.toList().forEach { removeCard(it) }
        MoodStore.clear()
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (message.isSelfSender) return
        if (message.type?.isText != true) return

        val text = message.humanReadableRepr
        if (text.length < 2 || text.length > 500) return

        val talker = message.talker ?: return
        val key = MoodStore.keyOf(text = text, talker = talker, messageId = message.msgId)

        val existingCard = cards[view]
        if (existingCard != null && existingCard.key == key) return

        // 有缓存直接显示
        val cached = MoodStore.get(key)
        if (cached != null) {
            showCard(view, key, cached)
            return
        }

        // 先显示占位
        showCard(view, key, null)

        // 去重分析
        val claim = MoodStore.acquire(key) ?: return

        scope.launch {
            try {
                val mood = withContext(Dispatchers.IO) {
                    analyze(text, talker)
                }
                MoodStore.complete(claim, mood)
                withContext(Dispatchers.Main) {
                    showCard(view, key, mood)
                }
            } catch (e: Exception) {
                MoodStore.release(claim)
                WeLogger.e(TAG, "情绪分析失败", e)
                withContext(Dispatchers.Main) {
                    showCard(view, key, null, error = e.message)
                }
            }
        }
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo) {}
    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        removeCard(view)
    }

    private suspend fun analyze(text: String, talker: String): Mood {
        val payload = JevEmotionProtocol.payload(text, modelName)
        val body = httpPost(apiUrl, apiKey, payload.toString())

        val choices = JSONObject(body).optJSONArray("choices")
        val content = choices
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "") ?: throw IllegalStateException("API 返回格式异常")

        val cleaned = content
            .replace(Regex("```json\\s*"), "")
            .replace("```", "")
            .trim()

        return JevEmotionProtocol.parseEmotion(cleaned)
    }

    private suspend fun httpPost(url: String, key: String, body: String): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("API 返回 ${conn.responseCode}: $err")
            }
            conn.inputStream.bufferedReader().readText()
        }

    private fun showCard(view: View, key: String, mood: Mood?, error: String? = null) {
        removeCard(view)

        val parent = findInsertionPoint(view) ?: return
        val density = view.resources.displayMetrics.density

        val card = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_CARD
            setPadding(
                (12 * density).toInt(),
                (7 * density).toInt(),
                (12 * density).toInt(),
                (7 * density).toInt(),
            )
            setBackgroundColor(Color.argb(180, 20, 20, 25))
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (3 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            setOnClickListener {
                if (error != null) {
                    // 重试：清除当前卡片，重新分析
                    WeChatMessageViewApi.addLifecycleListener(this@ChatAiEmotionAnalysis)
                }
            }
        }

        val textView = TextView(view.context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#F0F1F5"))
            setLineSpacing((density).toFloat(), 1f)
            typeface = Typeface.DEFAULT_BOLD
        }

        when {
            mood != null -> {
                val emotion = mood.label
                val icon = getEmotionIcon(mood.emotions)
                val score = mood.emotions.entries
                    .sortedByDescending { it.value }
                    .joinToString("\n") { (k, v) ->
                        "${getEmotionIcon(mapOf(k to v))} ${JevEmotionProtocol.emotions[k] ?: k}: ${"%.0f".format(v * 100)}%"
                    }
                textView.text = "$icon $emotion\n$score"
            }
            error != null -> {
                textView.setTextColor(Color.parseColor("#FF8A80"))
                textView.text = "分析失败：$error\n点击重试"
            }
            else -> {
                textView.setTextColor(Color.parseColor("#90A4AE"))
                textView.text = "⏳ 正在分析…"
            }
        }

        card.addView(textView)
        parent.addView(card)

        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                removeCard(v)
            }
        }
        view.addOnAttachStateChangeListener(detach)
        cards[view] = CardState(key, card, detach)
    }

    /** 找到消息气泡的父容器，把卡片插在气泡下方 */
    private fun findInsertionPoint(view: View): ViewGroup? {
        var current: View? = view
        while (current != null) {
            val parent = current.parent
            if (parent is LinearLayout &&
                (parent as LinearLayout).orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                return parent
            }
            current = parent as? View
        }
        return null
    }

    private fun removeCard(view: View) {
        val state = cards.remove(view) ?: return
        view.removeOnAttachStateChangeListener(state.detach)
        (state.view.parent as? ViewGroup)?.removeView(state.view)
    }

    private fun getEmotionIcon(emotions: Map<String, Double>): String {
        val max = emotions.maxByOrNull { it.value }?.key ?: return "🤔"
        return when (max) {
            "happy" -> "😊"
            "calm" -> "😐"
            "sad" -> "😔"
            "hurt" -> "😢"
            "annoyed" -> "😠"
            "relieved" -> "😌"
            "anxious" -> "😰"
            "confused" -> "😕"
            "tired" -> "😴"
            else -> "🤔"
        }
    }
}