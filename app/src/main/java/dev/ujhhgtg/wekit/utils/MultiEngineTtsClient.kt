package dev.ujhhgtg.wekit.utils

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.writeBytes

/**
 * 多引擎 TTS 客户端 — 移植自 WeAgent「AI语音助手」脚本（GPL 兼容，作者配置体系 tn/fh/yx/bv/vc）。
 *
 * 引擎契约（从脚本 TS()/FV()/VTS() 移植）：
 *  - FISH_AUDIO  GET https://yx520.ltd/API/fishaudio/api.php?ys=<voice>&text=<t>&apikey=<key>&msg=mp3
 *                音色列表 https://yx520.ltd/API/fishaudio/ys.php（"id. name" 逐行，偶数行为 id）
 *                返回：原始 mp3 字节（>256 字节且魔数合法）
 *  - YX520       GET https://yx520.ltd/API/wzzyypromax/api.php?voice=<v>&apikey=<key>&text=<t>
 *                音色列表 https://yx520.ltd/API/wzzyypromax/ys.php（"id. name" 逐行）
 *                返回：JSON {code/status, audio|url|data[.audio|.url]} → 二次下载；或原始 mp3
 *  - BYTE_DANCE  POST https://openspeech.bytedance.com/api/v3/tts/unidirectional
 *                Header: X-Api-Key / X-Api-Resource-Id(seed-tts-2.0|seed-icl-2.0) / X-Api-Request-Id
 *                Body: {req_params:{text,speaker,audio_params:{format:mp3,speech_rate,loudness_rate},post_process:{pitch},[model],[additions]}}
 *                返回：流式 JSON 行 {code,data(base64)}，code 20000000 结束
 *  - VOCU        POST https://v1.vocu.studio/api/tts/simple-generate (Bearer key)
 *                Body: {voiceId,text,promptId,preset,break_clone,language,vivid,speechRate,flash,stream:false,...}
 *                返回：mp3 字节或 JSON audio 字段（URL/base64）
 */
object MultiEngineTtsClient {

    private const val TAG = "MultiEngineTTS"

    const val FISH_YS_URL = "https://yx520.ltd/API/fishaudio/ys.php"
    const val FISH_API_URL = "https://yx520.ltd/API/fishaudio/api.php"
    const val YX_YS_URL = "https://yx520.ltd/API/wzzyypromax/ys.php"
    const val YX_API_URL = "https://yx520.ltd/API/wzzyypromax/api.php"
    const val BYTE_TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    const val VOCU_TTS_URL = "https://v1.vocu.studio/api/tts/simple-generate"
    const val VOCU_VOICE_URL = "https://v1.vocu.studio/api/voice?"

    private val httpClient = OkHttpClient.Builder().build()

    // ==================== 统一入口 ====================

    /**
     * 合成文本到 outputMp3。engine/voiceId/apiKey 语义随引擎不同：
     *  - FISH_AUDIO: voiceId=音色 id（ys.php 偶数行）
     *  - YX520: voiceId=音色名
     *  - BYTE_DANCE: voiceId=speaker（如 zh_female_vv_uranus_bigtts），apiKey=访问令牌
     *  - VOCU: voiceId=voiceId（服务端音色 id），apiKey=Bearer token
     */
    suspend fun synthesizeToMp3(
        engine: TtsEngine,
        text: String,
        outputMp3: Path,
        voiceId: String,
        apiKey: String,
    ): Result<Path> = runCatching {
        require(text.isNotBlank()) { "text 不能为空" }
        require(apiKey.isNotBlank()) { "缺少 ${engine.label} API Key，请先在语音面板配置中填写" }
        require(voiceId.isNotBlank()) { "请先选择 ${engine.label} 音色" }
        withContext(Dispatchers.IO) {
            val bytes = when (engine) {
                TtsEngine.FISH_AUDIO -> synthesizeFishAudio(text, voiceId, apiKey)
                TtsEngine.YX520 -> synthesizeYx520(text, voiceId, apiKey)
                TtsEngine.BYTE_DANCE -> synthesizeByteDance(text, voiceId, apiKey)
                TtsEngine.VOCU -> synthesizeVocu(text, voiceId, apiKey)
            }
            require(bytes.isNotEmpty()) { "${engine.label} 返回的音频为空" }
            outputMp3.writeBytes(bytes)
            WeLogger.i(TAG, "${engine.name} synthesize completed bytes=${bytes.size}")
            outputMp3
        }
    }.onFailure { error ->
        WeLogger.e(TAG, "${engine.name} synthesize failed", error)
    }

    // ==================== FISH_AUDIO ====================

    private suspend fun synthesizeFishAudio(text: String, voice: String, apiKey: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = FISH_API_URL +
                "?ys=" + URLEncoder.encode(voice, StandardCharsets.UTF_8.name()) +
                "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name()) +
                "&apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name()) +
                "&msg=mp3"
            var lastError: Throwable? = null
            // 脚本原逻辑重试 3 次
            repeat(3) { attempt ->
                try {
                    val bytes = httpGetBytes(url)
                    if (isMp3(bytes)) return@withContext bytes
                } catch (e: Throwable) {
                    lastError = e
                }
                if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
            }
            throw IllegalStateException("fishaudio 合成失败（已重试 3 次）", lastError)
        }

    // ==================== YX520 ====================

    private suspend fun synthesizeYx520(text: String, voice: String, apiKey: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = YX_API_URL +
                "?voice=" + URLEncoder.encode(voice, StandardCharsets.UTF_8.name()) +
                "&apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name()) +
                "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name())
            val raw = httpGetBytes(url)
            // 原始 mp3 直接返回
            if (isMp3(raw)) return@withContext raw
            // JSON: 解析 audio|url|data（含嵌套 data 对象），再二次下载
            val json = runCatching { JSONObject(String(raw, StandardCharsets.UTF_8)) }.getOrNull()
                ?: throw IllegalStateException("yx520 返回既非 mp3 也非 JSON")
            val code = json.optInt("code", json.optInt("status", -1))
            if (code != -1 && code != 200 && code != 0) {
                throw IllegalStateException("yx520 合成失败（code=$code）")
            }
            var audio = json.optString("audio", "").ifEmpty { json.optString("url", "") }
            if (audio.isEmpty()) audio = json.optString("data", "")
            if (audio.isEmpty()) {
                json.optJSONObject("data")?.let { data ->
                    audio = data.optString("audio", "").ifEmpty { data.optString("url", "") }
                }
            }
            require(audio.isNotEmpty()) { "yx520 响应缺少音频地址" }
            if (audio.startsWith("http")) {
                val bytes = httpGetBytes(audio)
                require(isMp3(bytes)) { "yx520 下载内容不是有效 mp3" }
                bytes
            } else {
                val decoded = android.util.Base64.decode(audio, android.util.Base64.DEFAULT)
                require(isMp3(decoded)) { "yx520 base64 内容不是有效 mp3" }
                decoded
            }
        }

    // ==================== BYTE_DANCE (火山/豆包) ====================

    private suspend fun synthesizeByteDance(text: String, speaker: String, apiKey: String): ByteArray =
        withContext(Dispatchers.IO) {
            // saturn 系音色（seed-icl）走 seed-icl-2.0 资源，其余走 seed-tts-2.0
            val saturn = speaker.startsWith("seed-icl") || speaker.contains("icl")
            val resourceId = if (saturn) "seed-icl-2.0" else "seed-tts-2.0"
            val body = JSONObject().apply {
                put(
                    "req_params",
                    JSONObject().apply {
                        put("text", text)
                        put("speaker", speaker)
                        put("audio_params", JSONObject().apply { put("format", "mp3") })
                        if (saturn) put("model", "seed-icl-2.0")
                    },
                )
            }
            val request = Request.Builder()
                .url(BYTE_TTS_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .build()
            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("byte_dance 合成失败：HTTP ${response.code}")
                }
                val audioBuf = java.io.ByteArrayOutputStream()
                val reader = java.io.BufferedReader(java.io.InputStreamReader(response.body.byteStream(), StandardCharsets.UTF_8))
                var finished = false
                reader.forEachLineCompat { line ->
                    if (finished || line.isBlank()) return@forEachLineCompat
                    val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLineCompat
                    val code = row.optInt("code", -1)
                    val b64 = row.optString("data", "")
                    if (b64.isNotEmpty()) audioBuf.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                    if (code == 20000000) finished = true
                    if (code != 0 && code != 3000 && code != 20000000) finished = true
                }
                val bytes = audioBuf.toByteArray()
                require(bytes.size > 256) { "byte_dance 音频数据不足（${bytes.size} 字节）" }
                bytes
            }
        }

    private inline fun java.io.BufferedReader.forEachLineCompat(action: (String) -> Unit) {
        use { reader ->
            var line = reader.readLine()
            while (line != null) {
                action(line)
                line = reader.readLine()
            }
        }
    }

    // ==================== VOCU ====================

    private suspend fun synthesizeVocu(text: String, voiceId: String, apiKey: String): ByteArray =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("voiceId", voiceId)
                put("text", text)
                put("promptId", "default")
                put("preset", "balance")
                put("break_clone", true)
                put("language", "auto")
                put("vivid", false)
                put("speechRate", 1.0)
                put("flash", false)
                put("stream", false)
                put("infinite_mode", false)
            }
            val request = Request.Builder()
                .url(VOCU_TTS_URL)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("vocu 合成失败：HTTP ${response.code}")
                }
                val raw = response.body.bytes()
                if (raw.size > 256 && isMp3(raw)) return@withContext raw
                val root = runCatching { JSONObject(String(raw, StandardCharsets.UTF_8)) }.getOrNull()
                    ?: throw IllegalStateException("vocu 返回既非 mp3 也非 JSON")
                val audio = findVocuAudio(root)
                require(audio.isNotEmpty()) { "vocu 响应缺少音频字段" }
                if (audio.startsWith("http")) {
                    val bytes = httpGetBytes(audio)
                    require(isMp3(bytes)) { "vocu 下载内容不是有效 mp3" }
                    bytes
                } else {
                    val decoded = android.util.Base64.decode(audio, android.util.Base64.DEFAULT)
                    require(isMp3(decoded)) { "vocu base64 内容不是有效 mp3" }
                    decoded
                }
            }
        }

    /** 递归查找 vocu 响应中的音频字段（audio/url/mp3/src，含 data 层嵌套，与脚本 VAE 一致）。 */
    private fun findVocuAudio(node: Any?, depth: Int = 0): String {
        if (node !is JSONObject || depth > 4) return ""
        for (key in listOf("audio", "url", "mp3", "src")) {
            val v = node.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        for (key in node.keys()) {
            val child = node.opt(key)
            if (child is JSONObject) {
                val found = findVocuAudio(child, depth + 1)
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    // ==================== 音色列表 ====================

    /**
     * 拉取音色列表。返回 (id, name) 对：
     *  - FISH_AUDIO: ys.php 奇数行 id / 偶数行名（脚本按 index%2 拆）
     *  - YX520: "id. name" 逐行
     */
    suspend fun fetchVoices(engine: TtsEngine, apiKey: String): Result<List<Pair<String, String>>> =
        runCatching {
            withContext(Dispatchers.IO) {
                when (engine) {
                    TtsEngine.FISH_AUDIO -> parseYsPairs(httpGetText(FISH_YS_URL), pairwise = true)
                    TtsEngine.YX520 -> parseYsPairs(httpGetText(YX_YS_URL), pairwise = false)
                    // BYTE_DANCE / VOCU 音色列表为服务端 JSON，由配置界面手动填 speaker/voiceId
                    TtsEngine.BYTE_DANCE, TtsEngine.VOCU -> emptyList()
                }
            }
        }.onFailure { error ->
            WeLogger.e(TAG, "${engine.name} fetch voices failed", error)
        }

    /** 解析 ys.php 文本：`1. 名字` 逐行。pairwise=true 时输出 (id, name) 成对交替行。 */
    private fun parseYsPairs(text: String, pairwise: Boolean): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (pairwise) {
            var i = 0
            while (i + 1 < lines.size) {
                val id = lines[i]
                val name = lines[i + 1]
                if (id.isNotEmpty() && name.isNotEmpty()) out.add(id to name)
                i += 2
            }
        } else {
            for (line in lines) {
                val dot = line.indexOf(". ")
                if (dot > 0) {
                    val id = line.substring(0, dot).trim()
                    val name = line.substring(dot + 2).trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) out.add(id to name)
                }
            }
        }
        return out
    }

    // ==================== HTTP 工具 ====================

    private suspend fun httpGetBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        httpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.bytes()
        }
    }

    private suspend fun httpGetText(url: String): String = withContext(Dispatchers.IO) {
        httpClient.newCall(
            Request.Builder().url(url).get()
                .header("User-Agent", "Mozilla/5.0")
                .build(),
        ).awaitResponse().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
    }

    /** MP3 魔数校验（ID3 或 MPEG 帧头），与脚本 isMP 一致。 */
    fun isMp3(data: ByteArray): Boolean {
        if (data.size < 3) return false
        if (data[0] == 0x49.toByte() && data[1] == 0x44.toByte() && data[2] == 0x33.toByte()) return true
        return (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xE0) == 0xE0
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

/** 多引擎 TTS 引擎标识。 */
enum class TtsEngine(val label: String) {
    FISH_AUDIO("FishAudio"),
    YX520("语星语音"),
    BYTE_DANCE("豆包语音"),
    VOCU("VoCu"),
}
