package dev.sun.wechat.features.items.chat.emotion

import java.util.concurrent.ConcurrentHashMap

/**
 * 情绪分析结果缓存 — 遵循言外 MoodStore 设计。
 *
 * 三条约束：
 * 1. 同一条消息及上下文不能重复请求模型 — 用 SHA-256 全量哈希做键。
 * 2. 界面线程要能立刻拿到结果 — "先占位、后填充"，装饰器拿到 null 先不画。
 * 3. 内存层；后台分析先从缓存恢复，再决定是否请求模型。
 */
data class Mood(
    val label: String,
    val score: Double,
    val raw: String,
    val detail: String = label,
    val emotions: Map<String, Double> = emptyMap(),
)

object MoodStore {

    private val cache = ConcurrentHashMap<String, Mood>()
    private val pending = ConcurrentHashMap<String, Claim>()

    class Claim internal constructor(val key: String)

    /** SHA-256 全量哈希：talker + text + speaker + context + messageId，与言外一致。 */
    fun keyOf(
        text: String,
        talker: String?,
        context: List<ContextMessage> = emptyList(),
        messageId: Long = 0,
        speaker: String = "对方",
    ): String {
        val source = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(talker.orEmpty())
            field(messageId.toString())
            field(speaker)
            field(text)
            context.forEach { field(it.speaker); field(it.text) }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): Mood? = cache[key]

    /** 认领一次分析任务；已在跑或已完成返回 null。 */
    @Synchronized
    fun acquire(key: String): Claim? {
        if (cache.containsKey(key) || pending.containsKey(key)) return null
        return Claim(key).also { pending[key] = it }
    }

    /** 分析完成，写入缓存。 */
    @Synchronized
    fun complete(claim: Claim, mood: Mood): Boolean {
        if (pending[claim.key] !== claim) return false
        cache[claim.key] = mood
        pending.remove(claim.key)
        return true
    }

    /** 失败释放认领，允许重试。 */
    @Synchronized
    fun release(claim: Claim): Boolean {
        if (pending[claim.key] !== claim) return false
        pending.remove(claim.key)
        return true
    }

    fun size(): Int = cache.size

    fun clear() { cache.clear(); pending.clear() }
}

data class ContextMessage(
    val speaker: String,
    val text: String,
)