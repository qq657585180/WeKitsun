package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager

/**
 * 三段解读（意图解析 / 可能在意 / 情绪倾向）走 WeAgent 的模型路由：
 * 用户在 WeAgent 里配好模型即可，情绪分析不再单独填 API Key。
 * 情绪概率判定仍走 JEV 协议；这一层只负责产出自然语言解读，失败时卡片退回只显示概率。
 */
object AgentLlm {

    /** WeAgent 里是否已配好可用模型。 */
    suspend fun isReady(): Boolean =
        runCatching { WeAgentRepository.firstModelId() != null }.getOrDefault(false)

    /**
     * 单轮对话补全，返回完整文本。模型未配置 / 调用失败时抛异常，由调用方降级。
     * 用法与 AiSmartReply 完全一致，只是不挂任何工具。
     */
    suspend fun complete(prompt: String): String {
        val modelId = WeAgentRepository.firstModelId()
            ?: throw IllegalStateException("未在 WeAgent 中配置模型")
        val model = WeAgentRepository.getModel(modelId)
            ?: throw IllegalStateException("模型 $modelId 不存在，请重新选择")
        val provider = WeAgentRepository.getModelProvider(model.providerId)
            ?: throw IllegalStateException("模型未绑定服务商")
        val client = ModelProviderManager.clientFor(provider)
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            tools = emptyList(),
            stream = true,
        )
        val sb = StringBuilder()
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> sb.append(event.text)
                is LlmStreamEvent.Completed ->
                    if (sb.isEmpty()) event.message.content?.let { sb.append(it) }
                is LlmStreamEvent.Failed -> throw event.error
                else -> {}
            }
        }
        return sb.toString().trim()
    }
}
