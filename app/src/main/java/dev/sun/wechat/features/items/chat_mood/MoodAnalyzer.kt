package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 分析调度器（对应 Yanwai SignalAnalyzer）：
 * MoodStore 按消息 key 去重、「先占位后填充」；Semaphore(2) 限并发；失败 30s 内不重试。
 * 界面层可先调 [submit] 拿 key，再用 [MoodStore.get] 读取（可能为 null 稍后由 UI 轮询/回调刷新）。
 */
object MoodAnalyzer {
    private val TAG = "MoodAnalyzer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Semaphore(1) 串行化：低 RPM 的 API 并发分析(每次2轮调用)也易打爆，先全串行
    private val slots = Semaphore(1)
    private val failures = ConcurrentHashMap<String, Long>()
    private val failureMessages = ConcurrentHashMap<String, String>()
    private val refreshListeners = CopyOnWriteArrayList<() -> Unit>()

    var enabled by KvStore.prefOption("mood_enabled", false)
    var showBadge by KvStore.prefOption("mood_show_badge", true)
    val header = "情绪分析"

    /** 界面层注册：某条消息分析完成/失败时触发重绘。 */
    fun onRefresh(l: () -> Unit) { refreshListeners.add(l) }
    fun refresh() { refreshListeners.forEach { runCatching { it.invoke() } } }

    fun failure(key: String): String? = failureMessages[key]
    fun retryFailure(key: String) {
        failures.remove(key); failureMessages.remove(key)
    }

    /**
     * 提交分析。返回消息 key（用于读结果）；已在跑/已完成/不满足条件返回 null 或 key。
     * [stillVisible] 每轮间检查一次：消息已滚出屏幕可返回 false 主动取消（省一次调用）。
     */
    fun submit(input: AnalysisInput, stillVisible: (() -> Boolean)? = null): String? {
        if (!enabled) return null
        if (MessagePolicy.textOrNull(input.text) == null) return null
        val key = input.key
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < 30_000L) return key
        if (!MoodStore.claim(key)) return key
        failureMessages.remove(key)
        scope.launch {
            try {
                slots.withPermit {
                    val visible = stillVisible ?: { true }
                    val mood = MoodTransport.analyze(input) { visible() }
                    MoodStore.complete(key, mood)
                    failures.remove(key); failureMessages.remove(key)
                    refresh()
                }
            } catch (e: CancellationException) {
                MoodStore.release(key)
            } catch (e: Exception) {
                failures[key] = System.currentTimeMillis()
                failureMessages[key] = e.message ?: "分析失败，请稍后重试"
                MoodStore.release(key)
                refresh()
                WeLogger.e(TAG, "分析失败 key=$key: ${e.message}")
            }
        }
        return key
    }

    /** 手动/面板触发：直接同步分析并返回结果（不计入 MoodStore 去重）。 */
    suspend fun requestMood(text: String, context: List<ContextMessage> = emptyList(), speaker: String = "对方"): Mood =
        try {
            MoodTransport.analyze(AnalysisInput(text, "manual", context, speaker = speaker))
        } catch (e: Exception) {
            Mood("分析失败", 0.0, 1, "", e.message ?: "未知错误")
        }
}