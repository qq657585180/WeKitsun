package dev.sun.wechat.features.items.batch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定时群发的标签与任务存储 + 每日调度器。
 *
 * - 标签：把若干群聊 wxid 打包成一个名字，发消息时按标签一键选定目标。
 * - 任务：一条消息 + 每天发送时间(HH:mm) + 目标(标签和/或手选群)，到点自动群发，每天只发一次。
 *
 * 数据以 JSON 存在 [KvStore]；调度器在微信主进程存活期间每分钟检查一次到点任务。
 */
internal object MassTaskStore {

    private const val TAG = "MassTaskStore"
    private const val KEY_TASKS = "mass_send_tasks_json"
    private const val KEY_TAGS = "mass_send_tags_json"

    /** Space out sends to avoid WeChat's server-side rate limiting. */
    private const val SEND_INTERVAL_MS = 800L

    // ---------------- 数据模型 ----------------

    internal data class MassTag(
        var name: String,
        var wxids: MutableSet<String> = mutableSetOf(),
    )

    internal data class MassTask(
        var id: String = UUID.randomUUID().toString(),
        var mode: String = MODE_TEXT,
        var content: String = "",
        var minutes: MutableSet<Int> = mutableSetOf(8 * 60), // 每天多个发送时间点(分钟数)
        var tags: MutableSet<String> = mutableSetOf(),
        var wxids: MutableSet<String> = mutableSetOf(),
        var enabled: Boolean = true,
        var sentDates: MutableSet<String> = mutableSetOf(), // 每个"日期+时间点"发送后记录，防重复
    ) {
        val sortedMinutes: List<Int> get() = minutes.toList().sorted()
    }

    // ---------------- 内存状态（UI 与调度器共享） ----------------

    var tasks by mutableStateOf(loadTasks())
        private set
    var tags by mutableStateOf(loadTags())
        private set

    private val ioMutex = Mutex()
    private var schedulerJob: Job? = null

    // ---------------- 存取 ----------------

    fun reload() {
        tasks = loadTasks()
        tags = loadTags()
    }

    fun saveTasks() {
        KvStore.putString(KEY_TASKS, JSONArray().apply {
            tasks.forEach { t ->
                put(JSONObject()
                    .put("id", t.id)
                    .put("mode", t.mode)
                    .put("content", t.content)
                    .put("minutes", JSONArray(t.minutes))
                    .put("tags", JSONArray(t.tags))
                    .put("wxids", JSONArray(t.wxids))
                    .put("enabled", t.enabled)
                    .put("sentDates", JSONArray(t.sentDates)))
            }
        }.toString())
    }

    fun saveTags() {
        KvStore.putString(KEY_TAGS, JSONArray().apply {
            tags.forEach { tag ->
                put(JSONObject()
                    .put("name", tag.name)
                    .put("wxids", JSONArray(tag.wxids)))
            }
        }.toString())
    }

    fun upsertTask(task: MassTask) {
        tasks = tasks.filterNot { it.id == task.id } + task
        saveTasks()
    }

    fun removeTask(taskId: String) {
        tasks = tasks.filterNot { it.id == taskId }
        saveTasks()
    }

    fun upsertTag(tag: MassTag) {
        tags = tags.filterNot { it.name == tag.name } + tag
        saveTags()
    }

    fun removeTag(name: String) {
        tags = tags.filterNot { it.name == name }
        saveTags()
        // 引用了该标签的任务自动解除引用（保留手选群）
        var changed = false
        tasks.forEach { if (it.tags.remove(name)) changed = true }
        if (changed) saveTasks()
    }

    /** 解析任务的实际发送目标：标签当前成员 ∪ 手选群，保持顺序去重。 */
    fun resolveTargets(task: MassTask): List<String> {
        val result = LinkedHashSet<String>()
        task.tags.forEach { name -> tags.firstOrNull { it.name == name }?.let { result += it.wxids } }
        result += task.wxids
        return result.toList()
    }

    // ---------------- JSON 解析 ----------------

    private fun loadTasks(): List<MassTask> = runCatching {
        val raw = KvStore.getString(KEY_TASKS) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val minutes = o.optJSONArray("minutes").toStringSet()
                .mapNotNull { it.toIntOrNull() }
                .filter { it in 0..24 * 60 - 1 }
                .toMutableSet()
                .ifEmpty { mutableSetOf(8 * 60) }
            // 兼容旧版单时间字段
            if (o.has("hour")) {
                minutes.add(o.optInt("hour", 8).coerceIn(0, 23) * 60 + o.optInt("minute", 0).coerceIn(0, 59))
            }
            MassTask(
                id = o.optString("id", UUID.randomUUID().toString()),
                mode = o.optString("mode", MODE_TEXT),
                content = o.optString("content", ""),
                minutes = minutes,
                tags = o.optJSONArray("tags").toStringSet().toMutableSet(),
                wxids = o.optJSONArray("wxids").toStringSet().toMutableSet(),
                enabled = o.optBoolean("enabled", true),
                sentDates = o.optJSONArray("sentDates").toStringSet().toMutableSet(),
            )
        }
    }.getOrElse {
        WeLogger.e(TAG, "failed to load mass tasks", it)
        emptyList()
    }

    private fun loadTags(): List<MassTag> = runCatching {
        val raw = KvStore.getString(KEY_TAGS) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MassTag(
                name = o.optString("name", ""),
                wxids = o.optJSONArray("wxids").toStringSet().toMutableSet(),
            )
        }.filter { it.name.isNotBlank() }
    }.getOrElse {
        WeLogger.e(TAG, "failed to load mass tags", it)
        emptyList()
    }

    private fun org.json.JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { k ->
            runCatching { getString(k) }.getOrNull()
        }.toSet()
    }

    // ---------------- 每日调度 ----------------

    /** 启动每分钟 tick（幂等）。在功能 onEnable 时调用。 */
    fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                runCatching { checkAndSendDueTasks() }
                    .onFailure { WeLogger.e(TAG, "scheduler tick failed", it) }
                // 对齐到下一分钟
                delay(60_000 - System.currentTimeMillis() % 60_000 + 500)
            }
        }
        WeLogger.i(TAG, "mass task scheduler started")
    }

    fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
        WeLogger.i(TAG, "mass task scheduler stopped")
    }

    private fun todayString(): String = Calendar.getInstance().let {
        "%04d-%02d-%02d".format(
            it.get(Calendar.YEAR),
            it.get(Calendar.MONTH) + 1,
            it.get(Calendar.DAY_OF_MONTH),
        )
    }

    private suspend fun checkAndSendDueTasks() {
        val now = Calendar.getInstance()
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val today = todayString()

        val due = tasks.filter { it.enabled && minuteOfDay in it.minutes && !it.sentDates.contains(sentKey(today, minuteOfDay)) }
        if (due.isEmpty()) return

        due.forEach { task ->
            val targets = resolveTargets(task)
            if (targets.isEmpty()) {
                // 无目标：标记该时间点已处理，避免每分钟重复报错
                markSent(task.id, today, minuteOfDay)
                return@forEach
            }

            var success = 0
            targets.forEachIndexed { index, wxId ->
                val sent = runCatching {
                    if (task.mode == MODE_CARD) WeMessageApi.sendXmlAppMsg(wxId, task.content)
                    else WeMessageApi.sendText(wxId, task.content)
                }.getOrElse {
                    WeLogger.e(TAG, "scheduled send failed to $wxId", it)
                    false
                }
                if (sent) success++
                if (index < targets.size - 1) delay(SEND_INTERVAL_MS.milliseconds)
            }

            markSent(task.id, today, minuteOfDay)
            showToastSuspend(
                localizedBatchString(
                    dev.sun.wechat.R.string.mass_task_sent_done,
                    success,
                    targets.size,
                ),
            )
            WeLogger.i(TAG, "scheduled task ${task.id} @${minuteOfDay / 60}:${minuteOfDay % 60} sent $success/${targets.size}")
        }
    }

    /** 防重复 key：日期 + 时间点（分钟数）。每个时间点每天只发一次。 */
    private fun sentKey(date: String, minuteOfDay: Int): String = "$date#$minuteOfDay"

    /** 清理 7 天前的历史记录，防止 sentDates 无限膨胀。 */
    private fun pruneSentDates(task: MassTask, today: String) {
        val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .parse(today)?.time?.minus(7L * 24 * 60 * 60 * 1000) ?: return
        val cutoffStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(cutoff))
        task.sentDates.removeAll { key ->
            val datePart = key.substringBefore("#")
            datePart < cutoffStr
        }
    }

    private suspend fun markSent(taskId: String, date: String, minuteOfDay: Int) {
        ioMutex.withLock {
            val task = tasks.firstOrNull { it.id == taskId } ?: return
            task.sentDates.add(sentKey(date, minuteOfDay))
            pruneSentDates(task, date)
            saveTasks()
        }
    }

    internal const val MODE_TEXT = "TEXT"
    internal const val MODE_CARD = "CARD"
}
