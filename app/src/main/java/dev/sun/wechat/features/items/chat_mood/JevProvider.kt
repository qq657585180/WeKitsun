package dev.sun.wechat.features.items.chat_mood

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Jev/TypeSafe 模型渠道预设（言外原版用的 Jev 1.13，TypeSafe 协议）。
 * 走 systemone 接口（state/questions 结构），非标准 Chat Completions。
 * 每个渠道附带官方网站，方便用户注册获取 API Key。
 */
enum class JevProvider(
    val id: String, val label: String, val endpoint: String, val model: String, val website: String,
) {
    TYPESAFE("typesafe", "Jev 官方 · TypeSafe", "https://api.typesafe.ai/v1/systemone", "jev-1.13.0",
        "https://typesafe.ai"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/systemone", "typesafe/jev-1.13",
        "https://openrouter.ai/typesafe/jev-1.13"),
    VERCEL("vercel", "Vercel AI Gateway", "https://ai-gateway.vercel.sh/typesafe/v1/systemone", "typesafe-ai/jev",
        "https://vercel.com/dashboard"),
    CUSTOM("custom", "自定义 Jev 兼容接口", "", "jev-1.13.0", "");

    companion object {
        fun resolve(id: String?, endpoint: String): JevProvider {
            entries.firstOrNull { it.id == id }?.let { return it }
            if (endpoint.isBlank()) return TYPESAFE
            val url = endpoint.trim().toHttpUrlOrNull() ?: return CUSTOM
            if (url.scheme != "https" || url.port != 443 || url.query != null || url.fragment != null ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return CUSTOM
            val paths = when (url.host) {
                "api.typesafe.ai" -> setOf("", "/v1", "/v1/systemone")
                "openrouter.ai" -> setOf("", "/api", "/api/v1", "/api/v1/systemone", "/api/alpha/decisions", "/api/v1/chat/completions")
                "ai-gateway.vercel.sh" -> setOf("", "/v1", "/v1/evaluate", "/v1/chat/completions", "/typesafe", "/typesafe/v1/systemone")
                else -> return CUSTOM
            }
            if (url.encodedPath.trimEnd('/') !in paths) return CUSTOM
            return when (url.host) {
                "api.typesafe.ai" -> TYPESAFE
                "openrouter.ai" -> OPENROUTER
                else -> VERCEL
            }
        }
    }
}
