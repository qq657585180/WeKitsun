package dev.ujhhgtg.wekit.features.items.chat.panel.voice

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSettings
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelSource
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceItem
import dev.ujhhgtg.wekit.features.items.chat.panel.VoiceProviderPage
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自定义在线语音目录源。
 *
 * 用户提供一个 JSON 目录 URL（MMKV `voice_directory_url`），GET 后解析
 * `{ "items": [ { "name", "url", "format?" } ] }`，返回可下载的语音列表。
 * format 缺省时由 url 扩展名推断。
 */
object CustomDirectoryVoiceProvider : VoiceProvider {

    override val id = "custom_directory"
    override val name = "自定义目录"

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> = runCatching {
        require(parent == null) { localizedChatString(R.string.chat_voice_category_invalid) }
        val directoryUrl = PanelSettings.voiceDirectoryUrl
        require(directoryUrl.isNotBlank()) { localizedChatString(R.string.chat_voice_directory_url_unconfigured) }
        val body = getText(directoryUrl)
        parseDirectory(body)
    }.logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> = runCatching {
        VoiceProviderPage(emptyList(), 0, false)
    }.logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        require(item.id.startsWith("custom:")) { localizedChatString(R.string.chat_voice_item_invalid) }
        require(!item.remoteUrl.isNullOrBlank()) { localizedChatString(R.string.chat_voice_download_url_unavailable) }
        item
    }

    private fun parseDirectory(body: String): VoiceProviderPage {
        val items = DefaultJson.parseToJsonElement(body)
            .jsonObject["items"]
            ?.jsonArray
            ?: error(localizedChatString(R.string.chat_voice_server_empty))
        val result = items.map { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val url = obj["url"]?.jsonPrimitive?.content.orEmpty()
            require(name.isNotBlank() && url.isNotBlank()) { localizedChatString(R.string.chat_voice_server_empty) }
            VoiceItem(
                id = "custom:$url",
                title = name,
                remoteUrl = url,
                source = PanelSource.ONLINE,
                format = obj["format"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: inferFormat(url),
            )
        }
        return VoiceProviderPage(result, 0, false)
    }

    private fun inferFormat(url: String): String = when {
        url.endsWith(".aac", ignoreCase = true) -> "aac"
        url.endsWith(".wav", ignoreCase = true) -> "wav"
        url.endsWith(".m4a", ignoreCase = true) -> "m4a"
        url.endsWith(".amr", ignoreCase = true) -> "amr"
        url.endsWith(".silk", ignoreCase = true) -> "silk"
        else -> "mp3"
    }
}
