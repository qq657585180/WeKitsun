package dev.sun.wechat.features.items.chat_mood

import org.json.JSONObject

/**
 * 「智能分析」提示词与解析（对应官方言外的 intent 那一轮）。
 *
 * 只产出自然语言解读，不参与情绪概率判定：
 * 情绪概率走 JEV 协议，这层失败时卡片退回只显示概率。
 */
object SmartProtocol {

    /** 附在第一轮 JEV 答题之后，让模型接着解释言外之意。 */
    fun prompt(text: String): String = buildString {
        appendLine("已得到情绪概率。现在只做言外之意解读，不要重复概率。")
        appendLine("消息：" + text)
        appendLine("只输出 JSON，不要解释、不要 markdown 代码块：")
        appendLine("{\"intent\":\"对方这句话想表达的意图，20字内\",\"concern\":\"对方可能在意的点，20字内\",\"tone\":\"情绪倾向，如不耐烦/试探/撒娇，10字内\",\"confidence\":0.8}")
        append("无法判断的字段填空字符串。")
    }

    /**
     * 从模型回复里抠出 JSON 对象。
     * 模型常带前后解释或 ```json 包裹，所以先截取第一个 { 到最后一个 }。
     */
    fun parse(reply: String): MoodReading {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return MoodReading.EMPTY
        return try {
            val json = JSONObject(reply.substring(start, end + 1))
            MoodReading(
                intent = json.optString("intent").trim(),
                concern = json.optString("concern").trim(),
                tone = json.optString("tone").trim(),
                confidence = json.optDouble("confidence", 0.0).takeIf { it.isFinite() } ?: 0.0,
            )
        } catch (e: Exception) {
            MoodReading.EMPTY
        }
    }

    /** 卡片上的三段文案；全空时调用方会跳过这一块。 */
    fun render(reading: MoodReading): String {
        val lines = mutableListOf<String>()
        if (reading.intent.isNotBlank()) lines.add("意图解析：" + reading.intent)
        if (reading.concern.isNotBlank()) lines.add("可能在意：" + reading.concern)
        if (reading.tone.isNotBlank()) lines.add("情绪倾向：" + reading.tone)
        return lines.joinToString("\n")
    }
}
