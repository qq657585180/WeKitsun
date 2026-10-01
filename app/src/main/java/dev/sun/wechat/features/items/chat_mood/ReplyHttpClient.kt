package dev.sun.wechat.features.items.chat_mood

import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 回复模型 HTTP 客户端（独立于 Jev 情绪分析）。
 * 支持帮我回（生成回复建议）和找话题（生成话题建议）两个路径。
 */
object ReplyHttpClient {
    private val TAG = "ReplyHttp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .build()

    /** 生成回复建议 */
    suspend fun generateReply(payload: JSONObject): ReplySuggestion = withContext(Dispatchers.IO) {
        val (endpoint, apiKey, _) = ReplyConfig.resolveModel()
            ?: throw IllegalStateException("请先选择回复模型")
        val body = request(endpoint, apiKey, payload)
        ReplyProtocol.parseReply(body)
    }

    /** 找话题 */
    suspend fun findTopics(payload: JSONObject): List<ReplyProtocol.TopicSuggestion> = withContext(Dispatchers.IO) {
        val (endpoint, apiKey, _) = ReplyConfig.resolveModel()
            ?: throw IllegalStateException("请先选择回复模型")
        val body = request(endpoint, apiKey, payload)
        ReplyProtocol.parseTopics(body)
    }

    private suspend fun request(endpoint: String, apiKey: String, payload: JSONObject): String {
        val call = client.newCall(
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive)
                        continuation.resumeWithException(IllegalStateException("连接超时或网络不可用，请重试"))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) { response.close(); return }
                    try {
                        val body: String = response.use {
                            val reason = when (it.code) {
                                401, 403 -> "API Key 或模型权限不可用，请检查模型配置"
                                402 -> "模型账户额度不足"
                                429 -> "请求过于频繁，请稍后重试"
                                400, 404, 422 -> "接口地址或模型不支持，请检查配置"
                                in 300..399 -> "接口发生重定向，请填写最终地址"
                                else -> if (it.isSuccessful) null else "模型服务暂不可用（HTTP ${it.code}）"
                            }
                            check(reason == null) { reason }
                            val source = requireNotNull(it.body).source()
                            source.request(1024 * 1024L + 1)
                            check(source.buffer.size <= 1024 * 1024L) { "模型响应过长" }
                            source.readUtf8()
                        }
                        continuation.resume(body)
                    } catch (error: IllegalStateException) {
                        continuation.resumeWithException(error)
                    } catch (error: Exception) {
                        continuation.resumeWithException(IllegalStateException("读取回复失败，请检查网络后重试", error))
                    }
                }
            })
        }
    }
}