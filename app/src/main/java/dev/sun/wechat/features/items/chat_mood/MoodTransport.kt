package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.data.KvStore
import kotlinx.coroutines.CancellationException

/**
 * 传输层：只用 Jev/TypeSafe 协议完成两轮分析。
 * Round1 得 ChatProfile，Round2 得精选卡 + 动作，最终返回 Mood。
 */
object MoodTransport {

    var jevProviderId by KvStore.prefOption("mood_jev_provider", "typesafe")
    var jevKey by KvStore.prefOption("mood_jev_key", "")
    var jevEndpoint by KvStore.prefOption("mood_jev_endpoint", ApiSettings.DEFAULT_ENDPOINT)
    var jevModel by KvStore.prefOption("mood_jev_model", "")

    private val jevClient = JevHttpClient()

    private fun jevSettings(): ApiSettings =
        ApiSettings.fromInput(jevEndpoint, jevKey, jevProviderId, jevModel)

    suspend fun analyze(input: AnalysisInput, shouldContinue: suspend () -> Boolean = { true }): Mood {
        val s = jevSettings()
        val profile = JevProtocol.parseProfile(
            jevClient.exchange(JevProtocol.payload(input.text, s.model, input.context, input.speaker), s),
        )
        if (!shouldContinue()) throw CancellationException("分析已停止")
        val cards = ChatTemplates.candidates(profile)
        val actions = ChatActions.candidates(profile)
        if (cards.isEmpty() && actions.isEmpty()) return JevProtocol.fallback(profile)
        return JevProtocol.parseDetail(
            jevClient.exchange(JevProtocol.detailPayload(input, s.model, profile), s),
            profile,
        )
    }
}
