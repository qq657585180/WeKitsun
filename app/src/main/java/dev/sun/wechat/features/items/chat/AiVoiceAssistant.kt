package dev.sun.wechat.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.agent.data.WeAgentRepository
import dev.sun.wechat.agent.model.LlmMessage
import dev.sun.wechat.agent.model.LlmRole
import dev.sun.wechat.agent.model.LlmStreamEvent
import dev.sun.wechat.agent.model.ModelProviderManager
import dev.sun.wechat.features.api.core.WeDatabaseListenerApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.core.models.MessageType
import dev.sun.wechat.features.api.ui.WeCurrentConversationApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.features.items.chat.panel.voice.TIAX_PRESET_VOICES
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.AudioUtils
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.MultiEngineTtsClient
import dev.sun.wechat.utils.TtsEngine
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.toPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 智能语音助手：收到触发词开头的消息 → 调用 WeAgent 模型库中的 AI 模型 → 用多引擎 TTS
 * 合成语音 → 转 silk 发送回聊天窗口。模型直接复用 WeAgent 配置的 provider/模型。
 *
 * 音色内置：豆包(122)/天X(459)；FishAudio/yx520 可拉取。支持面板内打字转语音发送。
 */
object AiVoiceAssistant : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "AI语音助手"
    override val nameRes = R.string.feature_ai_voice_assistant_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_voice_assistant_description

    private const val TAG = "AiVoiceAssistant"

    override val noSwitchWidget = true

    // ---- 配置 ----
    var autoReplyEnabled by KvStore.prefOption("aivoice_enabled", false)
    var triggerWord by KvStore.prefOption("aivoice_trigger", "*")
    var memoryRounds by KvStore.prefOption("aivoice_memory_rounds", 5)
    var voiceOnly by KvStore.prefOption("aivoice_voice_only", true)
    var weAgentModelId by KvStore.prefOption("aivoice_weagent_model", "")
    var prompt by KvStore.prefOption("aivoice_prompt", "你是一个乐于助人的AI助手")

    // ---- TTS 引擎配置 ----
    var engine by KvStore.prefOption("aivoice_engine", "fishaudio")
    var fishKey by KvStore.prefOption("aivoice_fish_key", "")
    var fishVoice by KvStore.prefOption("aivoice_fish_voice", "")
    var yxKey by KvStore.prefOption("aivoice_yx_key", "")
    var yxVoice by KvStore.prefOption("aivoice_yx_voice", "")
    var bvKey by KvStore.prefOption("aivoice_bv_key", "")
    var bvVoice by KvStore.prefOption("aivoice_bv_voice", "")
    var vocuKey by KvStore.prefOption("aivoice_vocu_key", "")
    var vocuVoice by KvStore.prefOption("aivoice_vocu_voice", "")
    var tiaxKey by KvStore.prefOption("aivoice_tiax_key", "")
    var tiaxVoice by KvStore.prefOption("aivoice_tiax_voice", "")

    // ---- 对话记忆 ----
    private val memories = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        loadMemories()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        memories.clear()
    }

    override fun onClick(context: ComponentActivity) {
        AiVoiceSettingsDialog.show(context)
    }

    // ================= 消息监听：触发 AI 回复 + #tts 指令 =================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val isSend = values.getAsInteger("isSend") ?: 1
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return

        // #tts 指令：自己发的消息，#tts 文字 → 转语音
        if (isSend != 0 && content.trim().startsWith("#tts")) {
            val text = content.trim().removePrefix("#tts").trim()
            if (text.isNotEmpty() && talker.isNotEmpty()) {
                scope.launch {
                    val ok = synthesizeAndSendText(talker, text)
                    if (ok) showToastSuspend("语音已发送")
                    else showToastSuspend("语音合成失败，请检查引擎配置")
                }
            }
            return
        }

        // AI 自动回复：收到的消息
        if (isSend != 0) return
        if (!autoReplyEnabled) return
        val tg = triggerWord.ifBlank { "*" }
        val clean = if (content.contains(":\n")) {
            val idx = content.indexOf(":\n")
            if (idx in 1..50) content.substring(idx + 2).trim() else content.trim()
        } else content.trim()
        if (!clean.startsWith(tg)) return
        val question = clean.substring(tg.length).trim().ifEmpty { clean }
        scope.launch { handleAiReply(talker, question) }
    }

    private suspend fun handleAiReply(talker: String, question: String) {
        try {
            val reply = askAi(talker, question)
            if (reply.startsWith("[")) {
                showToastSuspend(reply)
                return
            }
            if (voiceOnly) {
                sendAiVoice(talker, reply)
            } else {
                WeMessageApi.sendText(talker, reply)
            }
        } catch (error: Exception) {
            WeLogger.e(TAG, "AI reply failed", error)
            showToastSuspend("AI处理异常: ${error.message}")
        }
    }

    // ================= WeAgent 模型调用 =================

    private suspend fun askAi(talker: String, question: String): String =
        withContext(Dispatchers.IO) {
            try {
                val modelId = weAgentModelId.ifBlank {
                    WeAgentRepository.firstModelId()
                        ?: throw IllegalStateException("未配置AI模型，请先在 WeAgent 设置中添加模型")
                }
                val model = WeAgentRepository.getModel(modelId)
                    ?: throw IllegalStateException("未找到模型: $modelId")
                val provider = WeAgentRepository.getModelProvider(model.providerId)
                    ?: throw IllegalStateException("未找到模型提供者: ${model.providerId}")
                val client = ModelProviderManager.clientFor(provider)

                val messages = mutableListOf<LlmMessage>()
                messages += LlmMessage(LlmRole.SYSTEM, prompt)
                memories[talker]?.forEach { m ->
                    val role = when (m.optString("role")) {
                        "assistant" -> LlmRole.ASSISTANT
                        "user" -> LlmRole.USER
                        else -> return@forEach
                    }
                    messages += LlmMessage(role, m.optString("content"))
                }
                messages += LlmMessage(LlmRole.USER, question)

                val request = ModelProviderManager.buildRequest(
                    model = model,
                    messages = messages,
                    tools = emptyList(),
                    stream = true,
                )
                val reply = StringBuilder()
                client.stream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> reply.append(event.text)
                        is LlmStreamEvent.Completed -> {
                            if (reply.isEmpty()) reply.append(event.message.content ?: "")
                        }
                        is LlmStreamEvent.Failed -> throw event.error
                        else -> {}
                    }
                }
                val text = reply.toString().trim()
                if (text.isEmpty()) throw IllegalStateException("AI未生成有效回复")
                rememberMessage(talker, "user", question)
                rememberMessage(talker, "assistant", text)
                text
            } catch (error: Exception) {
                WeLogger.e(TAG, "askAi failed", error)
                "[${error.message}]"
            }
        }

    private fun rememberMessage(talker: String, role: String, content: String) {
        val list = memories.getOrPut(talker) { mutableListOf() }
        list.add(JSONObject().put("role", role).put("content", content))
        val max = memoryRounds.coerceIn(1, 999) * 2
        while (list.size > max) list.removeAt(0)
        saveMemories()
    }

    // ================= 语音发送 =================

    private suspend fun sendAiVoice(talker: String, text: String) {
        val ok = synthesizeAndSendText(talker, text)
        if (ok) showToastSuspend("AI语音已回复")
        else showToastSuspend("AI语音合成失败，请检查引擎配置")
    }

    private suspend fun synthesizeMp3(text: String): String? {
        val cacheDir = HostInfo.application.cacheDir
        val out = File(cacheDir, "ai_voice_${System.nanoTime()}.mp3").toPath()
        val result = when (engine) {
            "fishaudio" -> MultiEngineTtsClient.synthesizeToMp3(TtsEngine.FISH_AUDIO, text, out, fishVoice, fishKey)
            "yx520" -> MultiEngineTtsClient.synthesizeToMp3(TtsEngine.YX520, text, out, yxVoice, yxKey)
            "bv" -> MultiEngineTtsClient.synthesizeToMp3(TtsEngine.BYTE_DANCE, text, out, bvVoice, bvKey)
            "vocu" -> MultiEngineTtsClient.synthesizeToMp3(TtsEngine.VOCU, text, out, vocuVoice, vocuKey)
            "tiax" -> dev.sun.wechat.utils.TiaxTtsClient.synthesizeToMp3(
                text, out, tiaxVoice.toIntOrNull() ?: 0, tiaxKey)
            else -> null
        }
        return result?.getOrNull()?.toString()
    }

    /** 音色列表 (id to 名称)。豆包/天X 内置。 */
    fun engineVoices(engine: String): List<Pair<String, String>> = when (engine) {
        "bv" -> BYTE_DANCE_PRESET_VOICES
        "tiax" -> TIAX_PRESET_VOICES.mapIndexed { index, v -> index.toString() to v.name }
        else -> emptyList()
    }

    /** 拉取 FishAudio/yx520 音色。 */
    suspend fun fetchEngineVoices(engine: String): Result<List<Pair<String, String>>> = runCatching {
        when (engine) {
            "fishaudio" -> MultiEngineTtsClient.fetchVoices(TtsEngine.FISH_AUDIO, fishKey).getOrThrow()
            "yx520" -> MultiEngineTtsClient.fetchVoices(TtsEngine.YX520, yxKey).getOrThrow()
            else -> emptyList()
        }
    }

    /** 文本转语音并发送。 */
    suspend fun synthesizeAndSendText(talker: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val mp3 = synthesizeMp3(text) ?: return@runCatching false
                val silk = File(File(mp3).parentFile, "ai_${System.nanoTime()}.silk").absolutePath
                if (!AudioUtils.anyToSilk(mp3, silk)) return@runCatching false
                val duration = FakeVoiceDuration.fakeDurationMs()
                    .takeIf { it > 0L }
                    ?.toInt()
                    ?: AudioUtils.getDurationMs(silk).toInt()
                WeMessageApi.sendVoice(talker, silk, duration)
                true
            }.getOrElse {
                WeLogger.e(TAG, "synthesize+send failed", it)
                false
            }
        }

    /** 获取当前聊天会话 talker（面板文字转语音用）。 */
    fun currentTalker(): String? = WeCurrentConversationApi.value.takeIf { it.isNotBlank() }

    // ================= 记忆持久化 =================

    private fun memoryFile(): File = File(File(HostInfo.application.cacheDir, "aivoice"), "ai_memory.json")

    private fun loadMemories() {
        runCatching {
            val f = memoryFile()
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            root.keys().forEach { tk ->
                val arr = root.optJSONArray(tk) ?: return@forEach
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
                if (list.isNotEmpty()) memories[tk] = list
            }
        }.onFailure { WeLogger.e(TAG, "load memory failed", it) }
    }

    private fun saveMemories() {
        runCatching {
            val root = JSONObject()
            memories.forEach { (tk, list) -> if (list.isNotEmpty()) root.put(tk, JSONArray(list)) }
            val f = memoryFile()
            f.parentFile?.mkdirs()
            f.writeText(root.toString())
        }.onFailure { WeLogger.e(TAG, "save memory failed", it) }
    }

    fun clearAllMemories() {
        memories.clear()
        memoryFile().delete()
    }

    fun clearTalkerMemory(talker: String) {
        memories.remove(talker)
        saveMemories()
    }
}