package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.CancellationException

/**
 * 传输层：只用 Jev/TypeSafe 协议完成两轮分析。
 * Round1 得 ChatProfile，Round2 得精选卡 + 动作，最终返回 Mood。
 */
object MoodTransport {

    private const val TAG = "MoodTransport"

    var jevProviderId by KvStore.prefOption("mood_jev_provider", "typesafe")
    var jevKey by KvStore.prefOption("mood_jev_key", "")
    var jevEndpoint by KvStore.prefOption("mood_jev_endpoint", ApiSettings.DEFAULT_ENDPOINT)
    var jevModel by KvStore.prefOption("mood_jev_model", "")

    private val jevClient = JevHttpClient()

    private fun jevSettings(): ApiSettings =
        ApiSettings.fromInput(jevEndpoint, jevKey, jevProviderId, jevModel)

    suspend fun analyze(input: AnalysisInput, shouldContinue: suspend () -> Boolean = { true }): Mood {
        val s = jevSettings()
        val profile = try {
            JevProtocol.parseProfile(
                jevClient.exchange(JevProtocol.payload(input.text, s.model, input.context, input.speaker), s),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 协议校验失败（如 require 抛出的 Failed requirement.）必须带上原因，
            // 否则界面只会显示一句无从下手的「Failed requirement.」。
            throw IllegalStateException("模型返回不符合 Jev 协议（第一轮）：${e.message ?: e::class.simpleName}", e)
        }
        if (!shouldContinue()) throw CancellationException("分析已停止")
        val cards = ChatTemplates.candidates(profile)
        val actions = ChatActions.candidates(profile)
        if (cards.isEmpty() && actions.isEmpty()) return JevProtocol.fallback(profile)
        val detail = jevClient.exchange(JevProtocol.detailPayload(input, s.model, profile), s)
        if (!shouldContinue()) throw CancellationException("分析已停止")
        return try {
            JevProtocol.parseDetail(detail, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 第二轮只是「精选提醒」，模型偶尔答得不合规范时不该让整次分析报废，
            // 退回第一轮已得到的情绪概率，卡片依然有内容可看。
            WeLogger.w(TAG, "second pass rejected, falling back to profile", e)
            JevProtocol.fallback(profile)
        }
    }

    /**
     * 三段解读（意图解析 / 可能在意 / 情绪倾向）单独走 WeAgent 模型。
     * 用户在 WeAgent 里配了模型才有这段；没配 / 失败就返回空，卡片只显示概率。
     */
    suspend fun readingFor(input: AnalysisInput, mood: Mood): MoodReading {
        if (!AgentLlm.isReady()) return MoodReading.EMPTY
        return try {
            SmartProtocol.parse(AgentLlm.complete(SmartProtocol.prompt(input.text)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WeLogger.w(TAG, "intent reading failed, keeping probability-only", e)
            MoodReading.EMPTY
        }
    }

    /**
     * 完整一次分析：先跑 JEV 拿情绪概率，再补一段 WeAgent 的三段解读（可选）。
     */
    suspend fun analyzeSmart(input: AnalysisInput, mood: Mood): Mood {
        val reading = readingFor(input, mood)
        if (reading.isEmpty) return mood
        return mood.copy(reading = reading)
    }
}
