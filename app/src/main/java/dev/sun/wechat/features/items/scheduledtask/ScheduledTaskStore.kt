package dev.sun.wechat.features.items.scheduledtask

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sun.wechat.R
import dev.sun.wechat.data.KvStore
import dev.sun.wechat.utils.WeLogger
import java.util.Calendar
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledContentItem(
    val type: Int,
    val value: String,
)

data class ScheduledTask(
    var id: String = UUID.randomUUID().toString(),
    var remark: String = "",
    var targetType: Int = ScheduledTaskStore.TARGET_CHAT,
    var items: MutableList<ScheduledContentItem> = mutableListOf(),
    var targetIds: MutableList<String> = mutableListOf(),
    var planTimes: MutableList<Long> = mutableListOf(),
    var repeatType: Int = ScheduledTaskStore.REPEAT_NONE,
    var repeatDays: MutableSet<Int> = mutableSetOf(),
    var intervalSeconds: Int = 0,
    var mediaIntervalSeconds: Int = 0,
    var sendOnTimeout: Boolean = true,
    var momentsType: Int = ScheduledTaskStore.MOMENTS_TEXT,
    var enabled: Boolean = true,
    var status: String = ScheduledTaskStore.STATUS_PENDING,
    var lastExecutedTime: Long = 0L,
    var lastSuccessCount: Int = 0,
    var lastFailCount: Int = 0,
) {
    val sortedPlanTimes: List<Long> get() = planTimes.filter { it > 0L }.sorted()
    val firstPlanTime: Long get() = sortedPlanTimes.firstOrNull() ?: 0L
}

/**
 * 定时任务存储：任务模型 + JSON 持久化 + 下次计划时间计算。
 *
 * 任务 = 内容(文本/图片/视频/文件/表情/语音/XML，或朋友圈图文视频) +
 * 一个或多个计划时间 + 重复方式(不重复/每天/每周) + 目标(群/好友或朋友圈)。
 */
object ScheduledTaskStore {

    private const val TAG = "ScheduledTaskStore"
    private const val KEY_TASKS = "scheduled_task_items_v1"

    // 内容类型
    const val TYPE_TEXT = 0
    const val TYPE_IMAGE = 1
    const val TYPE_VIDEO = 2
    const val TYPE_FILE = 3
    const val TYPE_EMOJI = 4
    const val TYPE_VOICE = 5
    const val TYPE_XML = 6

    // 目标类型
    const val TARGET_CHAT = 0
    const val TARGET_MOMENTS = 1

    // 朋友圈类型
    const val MOMENTS_TEXT = 0
    const val MOMENTS_TEXT_IMAGE = 1
    const val MOMENTS_TEXT_VIDEO = 2
    const val MOMENTS_IMAGE = 3
    const val MOMENTS_VIDEO = 4

    // 重复方式
    const val REPEAT_NONE = 0
    const val REPEAT_DAILY = 1
    const val REPEAT_WEEKLY = 2

    // 状态
    const val STATUS_PENDING = "pending"
    const val STATUS_RUNNING = "running"

    var tasks by mutableStateOf(loadTasks())
        private set

    fun reload() {
        tasks = loadTasks()
    }

    fun findTask(id: String): ScheduledTask? = tasks.firstOrNull { it.id == id }

    fun saveTasks() {
        KvStore.putString(
            KEY_TASKS,
            JSONArray().apply {
                tasks.forEach { t ->
                    put(
                        JSONObject()
                            .put("id", t.id)
                            .put("remark", t.remark)
                            .put("targetType", t.targetType)
                            .put(
                                "items",
                                JSONArray().apply {
                                    t.items.forEach { item ->
                                        put(JSONObject().put("type", item.type).put("value", item.value))
                                    }
                                },
                            )
                            .put("targetIds", JSONArray(t.targetIds))
                            .put("planTimes", JSONArray(t.planTimes))
                            .put("repeatType", t.repeatType)
                            .put("repeatDays", JSONArray(t.repeatDays.sorted()))
                            .put("intervalSeconds", t.intervalSeconds)
                            .put("mediaIntervalSeconds", t.mediaIntervalSeconds)
                            .put("sendOnTimeout", t.sendOnTimeout)
                            .put("momentsType", t.momentsType)
                            .put("enabled", t.enabled)
                            .put("status", t.status)
                            .put("lastExecutedTime", t.lastExecutedTime)
                            .put("lastSuccessCount", t.lastSuccessCount)
                            .put("lastFailCount", t.lastFailCount),
                    )
                }
            }.toString(),
        )
    }

    fun upsertTask(task: ScheduledTask) {
        tasks = tasks.filterNot { it.id == task.id } + task
        saveTasks()
    }

    fun removeTask(id: String) {
        tasks = tasks.filterNot { it.id == id }
        saveTasks()
    }

    fun newDraft(now: Long = System.currentTimeMillis()): ScheduledTask {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ScheduledTask(planTimes = mutableListOf(calendar.timeInMillis))
    }

    // ---------------- 归一化 ----------------

    fun normalizedPlanTimes(task: ScheduledTask): List<Long> =
        task.planTimes.filter { it > 0L }.distinct().sorted()

    fun normalizedItems(task: ScheduledTask): List<ScheduledContentItem> =
        task.items.mapNotNull { item ->
            val type = item.type.takeIf { contentTypeIsValid(it) } ?: TYPE_TEXT
            val value = item.value.trim()
            if (value.isBlank()) null else ScheduledContentItem(type, value)
        }

    fun normalizedMomentsItems(task: ScheduledTask): List<ScheduledContentItem> {
        val raw = task.items
        val text = raw.firstOrNull { it.type == TYPE_TEXT }?.value?.trim().orEmpty()
        val images = raw.asSequence()
            .filter { it.type == TYPE_IMAGE }
            .map { it.value.trim() }
            .filter { it.isNotBlank() && java.io.File(it).isFile }
            .distinct()
            .take(9)
            .map { ScheduledContentItem(TYPE_IMAGE, it) }
            .toList()
        val video = raw.firstOrNull { it.type == TYPE_VIDEO }?.value?.trim().orEmpty()
            .takeIf { it.isNotBlank() && java.io.File(it).isFile }
            ?.let { ScheduledContentItem(TYPE_VIDEO, it) }
        val textItem = text.takeIf { it.isNotBlank() }?.let { ScheduledContentItem(TYPE_TEXT, it) }
        return when (task.momentsType) {
            MOMENTS_TEXT -> listOfNotNull(textItem)
            MOMENTS_TEXT_IMAGE -> listOfNotNull(textItem) + images
            MOMENTS_TEXT_VIDEO -> listOfNotNull(textItem, video)
            MOMENTS_IMAGE -> images
            MOMENTS_VIDEO -> listOfNotNull(video)
            else -> emptyList()
        }
    }

    @StringRes
    fun momentsValidationError(task: ScheduledTask): Int? {
        if (task.targetType != TARGET_MOMENTS) return null
        val items = normalizedMomentsItems(task)
        val hasText = items.any { it.type == TYPE_TEXT && it.value.isNotBlank() }
        val imageCount = items.count { it.type == TYPE_IMAGE }
        val videoCount = items.count { it.type == TYPE_VIDEO }
        return when (task.momentsType) {
            MOMENTS_TEXT -> if (hasText) null else R.string.scheduled_task_moments_need_text
            MOMENTS_TEXT_IMAGE -> when {
                !hasText -> R.string.scheduled_task_moments_need_text
                imageCount !in 1..9 -> R.string.scheduled_task_moments_need_images
                else -> null
            }
            MOMENTS_TEXT_VIDEO -> when {
                !hasText -> R.string.scheduled_task_moments_need_text
                videoCount != 1 -> R.string.scheduled_task_moments_need_video
                else -> null
            }
            MOMENTS_IMAGE -> if (imageCount in 1..9) null else R.string.scheduled_task_moments_need_images
            MOMENTS_VIDEO -> if (videoCount == 1) null else R.string.scheduled_task_moments_need_video
            else -> R.string.scheduled_task_moments_need_type
        }
    }

    // ---------------- 时间计算 ----------------

    fun resolveNextPlanTime(
        basePlanTime: Long,
        repeatType: Int,
        repeatDays: Set<Int>,
        now: Long = System.currentTimeMillis(),
    ): Long {
        if (basePlanTime <= 0L) return 0L
        if (repeatType == REPEAT_NONE) return basePlanTime
        var next = basePlanTime
        if (repeatType == REPEAT_WEEKLY) {
            val days = repeatDays.filter { it in validRepeatDays() }.toSet()
            if (days.isNotEmpty()) {
                val currentDay = Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_WEEK)
                if (currentDay !in days) {
                    next = calculateNextPlanTime(next, repeatType, days)
                }
            }
        }
        var guard = 0
        while (next <= now && guard < 400) {
            next = calculateNextPlanTime(next, repeatType, repeatDays)
            guard++
        }
        return next
    }

    fun calculateNextPlanTime(currentPlanTime: Long, repeatType: Int, repeatDays: Set<Int>): Long {
        if (currentPlanTime <= 0L) return 0L
        val calendar = Calendar.getInstance().apply { timeInMillis = currentPlanTime }
        when (repeatType) {
            REPEAT_DAILY -> calendar.add(Calendar.DAY_OF_MONTH, 1)
            REPEAT_WEEKLY -> {
                val days = repeatDays.filter { it in validRepeatDays() }.toSet()
                if (days.isEmpty()) {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                } else {
                    var safety = 14
                    do {
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                        safety--
                    } while (safety > 0 && calendar.get(Calendar.DAY_OF_WEEK) !in days)
                }
            }
        }
        return calendar.timeInMillis
    }

    fun validRepeatDays(): Set<Int> = setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY,
    )

    // ---------------- 校验 ----------------

    fun contentTypeIsValid(type: Int): Boolean = type in TYPE_TEXT..TYPE_XML

    fun targetTypeIsValid(type: Int): Boolean = type == TARGET_CHAT || type == TARGET_MOMENTS

    fun repeatTypeIsValid(type: Int): Boolean = type in REPEAT_NONE..REPEAT_WEEKLY

    fun momentsTypeIsValid(type: Int): Boolean = type in MOMENTS_TEXT..MOMENTS_VIDEO

    fun scheduledTypeUsesText(type: Int): Boolean = type == TYPE_TEXT || type == TYPE_XML

    // ---------------- JSON 解析 ----------------

    private fun loadTasks(): List<ScheduledTask> = runCatching {
        val raw = KvStore.getString(KEY_TASKS) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
            ScheduledTask(
                id = id,
                remark = o.optString("remark", ""),
                targetType = o.optInt("targetType", TARGET_CHAT)
                    .takeIf { targetTypeIsValid(it) } ?: TARGET_CHAT,
                items = parseItems(o.optJSONArray("items")),
                targetIds = o.optJSONArray("targetIds").toStringList().toMutableList(),
                planTimes = o.optJSONArray("planTimes").toLongList().toMutableList(),
                repeatType = o.optInt("repeatType", REPEAT_NONE)
                    .takeIf { repeatTypeIsValid(it) } ?: REPEAT_NONE,
                repeatDays = o.optJSONArray("repeatDays").toIntSet().toMutableSet(),
                intervalSeconds = o.optInt("intervalSeconds", 0).coerceIn(0, 3600),
                mediaIntervalSeconds = o.optInt("mediaIntervalSeconds", 0).coerceIn(0, 3600),
                sendOnTimeout = o.optBoolean("sendOnTimeout", true),
                momentsType = o.optInt("momentsType", MOMENTS_TEXT)
                    .takeIf { momentsTypeIsValid(it) } ?: MOMENTS_TEXT,
                enabled = o.optBoolean("enabled", true),
                status = o.optString("status", STATUS_PENDING).ifBlank { STATUS_PENDING },
                lastExecutedTime = o.optLong("lastExecutedTime", 0L),
                lastSuccessCount = o.optInt("lastSuccessCount", 0).coerceAtLeast(0),
                lastFailCount = o.optInt("lastFailCount", 0).coerceAtLeast(0),
            )
        }.sortedBy { it.firstPlanTime }
    }.getOrElse {
        WeLogger.e(TAG, "failed to load scheduled tasks", it)
        emptyList()
    }

    private fun parseItems(array: JSONArray?): MutableList<ScheduledContentItem> {
        if (array == null) return mutableListOf()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = obj.optInt("type", TYPE_TEXT)
                val value = obj.optString("value").trim()
                if (contentTypeIsValid(type) && value.isNotBlank()) {
                    add(ScheduledContentItem(type, value))
                }
            }
        }.toMutableList()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            runCatching { getString(index) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            runCatching { getLong(index) }.getOrNull()?.takeIf { it > 0L }
        }
    }

    private fun JSONArray?.toIntSet(): Set<Int> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                val value = optInt(index, Int.MIN_VALUE)
                if (value in validRepeatDays()) add(value)
            }
        }
    }
}
