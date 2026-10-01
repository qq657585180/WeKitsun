package dev.ujhhgtg.wekit.features.items.chat.emotion

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object JevEmotionClient {

    private const val TAG = "JevEmotion"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class EmotionResult(
        val emotion: String,
        val score: Int,
        val raw: String
    )

    suspend fun analyzeEmotion(
        text: String,
        apiUrl: String,
        apiKey: String,
        model: String,
        promptTemplate: String = DEFAULT_PROMPT
    ): Result<EmotionResult> = runCatching {
        require(text.isNotBlank()) { "文本不能为空" }
        require(apiUrl.isNotBlank()) { "API地址不能为空" }
        require(apiKey.isNotBlank()) { "API密钥不能为空" }

        withContext(Dispatchers.IO) {
            val prompt = promptTemplate.replace("{text}", text)

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.3)
                put("max_tokens", 100)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).awaitResponse()
            val body = response.body?.string() ?: throw IOException("响应为空")

            if (!response.isSuccessful) {
                throw IOException("API返回错误: ${response.code} - $body")
            }

            val result = parseEmotionResponse(body)
            WeLogger.d(TAG, "情绪分析完成: $result")
            result
        }
    }.onFailure { error ->
        WeLogger.e(TAG, "情绪分析失败", error)
    }

    private fun parseEmotionResponse(response: String): EmotionResult {
        try {
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
            val content = choices
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""

            val cleaned = content.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val result = try {
                JSONObject(cleaned)
            } catch (e: Exception) {
                parseTextResponse(cleaned)
            }

            return EmotionResult(
                emotion = result.optString("情绪", "未分类"),
                score = result.optInt("分数", 0),
                raw = cleaned
            )
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析情绪响应失败: ${e.message}")
            return EmotionResult("解析失败", 0, response)
        }
    }

    private fun parseTextResponse(text: String): JSONObject {
        val result = JSONObject()
        
        val emotionPattern = Regex("""情绪[:：]\s*(.+)""")
        val scorePattern = Regex("""分数[:：]\s*(\d+)""")
        
        emotionPattern.find(text)?.let {
            result.put("情绪", it.groupValues[1].trim())
        } ?: result.put("情绪", text.take(10))
        
        scorePattern.find(text)?.let {
            result.put("分数", it.groupValues[1].toIntOrNull() ?: 0)
        } ?: result.put("分数", 0)
        
        return result
    }

    private suspend fun Call.awaitResponse(): Response =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    const val DEFAULT_PROMPT = """请分析以下文本的情绪，返回JSON格式，包含"情绪"和"分数"两个字段。
情绪选项：开心、平静、失落、委屈、生气、缓和
分数：0-100，表示情绪的强度

文本：{text}

请只返回JSON，不要其他内容。示例：
{"情绪": "开心", "分数": 85}"""

    private const val SYSTEM_PROMPT = """你是一个专业的情绪分析助手。你会分析文本的情绪，并只返回JSON格式的结果。
情绪选项：开心、平静、失落、委屈、生气、缓和
分数范围：0-100，表示情绪强度"""
}