package dev.sun.wechat.features.items.chat_mood

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import dev.sun.wechat.features.api.core.models.MessageInfo
import dev.sun.wechat.features.api.ui.WeChatMessageViewApi
import dev.sun.wechat.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque

/**
 * 消息挂钩（对应 Yanwai MessageSniffer，改用 WeKit 的 WeChatMessageViewApi）：
 * 监听聊天消息行 View 绑定，逐条提交情绪分析并装饰卡片；按会话维护最近文字消息做上下文。
 */
object MessageSniffer {
    private val TAG = "MessageSniffer"
    private val subscribed = AtomicBoolean(false)
    private val recentByTalker = HashMap<String, ArrayDeque<Pair<String, String>>>()

    private val lifecycle = object : WeChatMessageViewApi.IMessageViewLifecycleListener {
        override fun onMessageViewAttached(view: View, message: MessageInfo) {
            try {
                if (!MoodAnalyzer.enabled) return
                if (message.type?.isText != true) return  // 非纯文字(图片/语音/文件/视频/链接/引用)不参与
                val talker = message.talker
                val text = MessagePolicy.textOrNull(message.humanReadableRepr) ?: return  // 空/超长不算正确文字
                val speaker = if (message.isSend != 0) "我" else "对方"
                val deque = recentByTalker.getOrPut(talker) { ArrayDeque() }
                deque.addLast(speaker to text)
                while (deque.size > 24) deque.removeFirst()
                // 情绪分析只分析/装饰对方的消息；自己发的消息仅进上下文，不出卡、不分析
                if (message.isSend != 0) return
                val context = deque.dropLast(1).takeLast(MessagePolicy.MAX_CONTEXT_MESSAGES)
                    .map { ContextMessage(it.first, it.second) }
                val input = AnalysisInput(
                    text = text,
                    talker = talker,
                    context = context,
                    messageId = message.createTime,
                    speaker = speaker,
                )
                MoodAnalyzer.submit(input) { view.isShown }
                BubbleDecorator.show(view, input)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "onMessageViewAttached failed", t)
            }
        }

        override fun onMessageViewDetached(view: View, message: MessageInfo) = BubbleDecorator.clear(view)
        override fun onMessageViewRecycled(view: View, message: MessageInfo) = BubbleDecorator.clear(view)
    }

    /** 右上角开关切换后触发：卡片在后续消息绑定/滚动时按 showBadge 重新绘制。 */
    fun refresh() {}

    /** 从 View 的 context 链里解析宿主 Activity。 */
    private fun viewActivity(v: View): Activity? {
        var ctx = v.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    /** 幂等订阅一次；由 MoodFeature 在启用时调用。 */
    fun ensureSubscribed() {
        if (subscribed.compareAndSet(false, true)) {
            WeChatMessageViewApi.addLifecycleListener(lifecycle)
        }
    }
}