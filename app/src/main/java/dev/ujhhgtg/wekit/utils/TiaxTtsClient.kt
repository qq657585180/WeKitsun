package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.writeBytes

/**
 * tiax（天XAPI）ULikeCam 版文字转语音客户端。
 *
 * 实测确认的接口契约（GET）：
 *   https://www.tiax.pw/API/yuyin2.php?apikey=<key>&text=<URL 编码文本>&voice=<1..458>
 * 成功返回 JSON：{"code":"200","url":"<bytecdn mp3 临时链接>"}，需二次下载 mp3。
 * 失败返回 JSON：{"code":400,"msg":"缺少 text 参数"} / {"code":403,...}。
 * voice 为 1-based 序号（音色列表 https://www.tiax.pw/API/ys.php），非法/越界时服务端回退音色 1。
 */
object TiaxTtsClient {

    private const val TAG = "TiaxTTS"
    private const val API_URL = "https://www.tiax.pw/API/yuyin2.php"
    private const val MAX_VOICE_INDEX = 458

    private val httpClient = OkHttpClient.Builder().build()

    suspend fun synthesizeToMp3(
        text: String,
        outputMp3: Path,
        voiceIndex: Int,
        apiKey: String,
    ): Result<Path> = runCatching {
        require(apiKey.isNotBlank()) { "缺少 tiax API Key，请先在语音面板配置中填写" }
        require(text.isNotBlank()) { "text 不能为空" }
        val clampedIndex = voiceIndex.coerceIn(0, MAX_VOICE_INDEX - 1) + 1
        withContext(Dispatchers.IO) {
            val url = API_URL +
                    "?apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name()) +
                    "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name()) +
                    "&voice=$clampedIndex"
            WeLogger.d(TAG, "synthesize start voiceIndex=$clampedIndex chars=${text.length}")
            val mp3Url = requestAudioUrl(url)
            WeLogger.d(TAG, "synthesize resolved mp3 url, downloading")
            val bytes = downloadBytes(mp3Url)
            require(bytes.isNotEmpty()) { "tiax 返回的 mp3 为空" }
            outputMp3.writeBytes(bytes)
            WeLogger.i(TAG, "synthesize completed bytes=${bytes.size}")
            outputMp3
        }
    }.onFailure { error ->
        WeLogger.e(TAG, "synthesize failed", error)
    }

    /** 请求合成并解析出 mp3 下载地址。 */
    private suspend fun requestAudioUrl(url: String): String = withContext(Dispatchers.IO) {
        httpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(body) ?: "tiax 请求失败：HTTP ${response.code}")
            }
            val obj = DefaultJson.parseToJsonElement(body).jsonObject
            val code = obj["code"]?.jsonPrimitive?.content
            if (code != "200") throw IllegalStateException(parseErrorMessage(body) ?: "tiax 合成失败（code=$code）")
            obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("tiax 响应缺少 mp3 下载地址")
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        httpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            check(response.isSuccessful) { "tiax mp3 下载失败：HTTP ${response.code}" }
            response.body.bytes()
        }
    }

    /** 尝试从 JSON 错误响应中提取消息（兼容嵌套 error.message 与旧版 msg 字段）。 */
    private fun parseErrorMessage(body: String): String? = try {
        val obj = DefaultJson.parseToJsonElement(body).jsonObject
        obj["msg"]?.jsonPrimitive?.content
            ?: obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            ?: obj["message"]?.jsonPrimitive?.content
    } catch (_: Throwable) {
        null
    }

    private suspend fun Call.awaitResponse(): Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
}
