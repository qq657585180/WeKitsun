package dev.sun.wechat.features.items.scheduledtask

import android.media.MediaMetadataRetriever
import android.os.PowerManager
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.WeMessageApi
import dev.sun.wechat.features.api.ui.WeMomentsApi
import dev.sun.wechat.utils.HostInfo
import dev.sun.wechat.utils.WeLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 定时任务调度器：在微信主进程存活期间周期性检查到点任务并发送。
 *
 * 采用协程 tick（每 15 秒）而非精确闹钟；发送期间持有 PARTIAL_WAKE_LOCK 防止休眠中断。
 */
object ScheduledTaskRuntime {

    private const val TAG = "ScheduledTaskRuntime"

    private const val TICK_INTERVAL_MS = 15_000L
    private const val ON_TIME_GRACE_MS = 60_000L
    private const val SINGLE_TIMEOUT_WINDOW_MS = 10 * 60 * 1000L
    private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    private const val WAKE_LOCK_TAG = "WeKit:ScheduledTask"
    private const val FRIEND_NAME_PLACEHOLDER = "%friendName%"

    private val ioMutex = Mutex()
    private var schedulerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningIds = ConcurrentHashMap.newKeySet<String>()

    fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        ScheduledTaskStore.reload()
        schedulerJob = scope.launch {
            while (isActive) {
                runCatching { ioMutex.withLock { processDueTasks() } }
                    .onFailure { WeLogger.e(TAG, "scheduler tick failed", it) }
                delay(TICK_INTERVAL_MS)
            }
        }
        WeLogger.i(TAG, "scheduled task runtime started")
    }

    fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
        WeLogger.i(TAG, "scheduled task runtime stopped")
    }

    /** 立即执行一次任务（不推进计划时间）。 */
    suspend fun runNow(taskId: String) {
        ioMutex.withLock {
            val task = ScheduledTaskStore.findTask(taskId) ?: return@withLock
            if (!runningIds.add(taskId)) return@withLock
            task.status = ScheduledTaskStore.STATUS_RUNNING
            ScheduledTaskStore.saveTasks()
            val wakeLock = acquireWakeLock()
            val result = try {
                runCatching { sendTask(task) }.getOrElse {
                    WeLogger.e(TAG, "run now failed: ${task.id}", it)
                    0 to expectedTargets(task)
                }
            } finally {
                releaseWakeLock(wakeLock)
            }
            val latest = ScheduledTaskStore.findTask(taskId) ?: return@withLock
            latest.status = ScheduledTaskStore.STATUS_PENDING
            latest.lastExecutedTime = System.currentTimeMillis()
            latest.lastSuccessCount = result.first
            latest.lastFailCount = result.second
            ScheduledTaskStore.saveTasks()
            runningIds.remove(taskId)
        }
    }

    // ---------------- 调度核心 ----------------

    private suspend fun processDueTasks() {
        val now = System.currentTimeMillis()
        val snapshot = ScheduledTaskStore.tasks.toList()
        for (task in snapshot) {
            if (!task.enabled) continue
            if (task.status == ScheduledTaskStore.STATUS_RUNNING) continue
            if (!runningIds.add(task.id)) continue
            try {
                val due = task.sortedPlanTimes.firstOrNull { it <= now } ?: continue
                if (shouldExecute(task, due, now)) {
                    execute(task.id, due)
                } else {
                    advance(task.id, due, 0, 0, executed = false)
                }
            } finally {
                runningIds.remove(task.id)
            }
        }
    }

    private fun shouldExecute(task: ScheduledTask, planTime: Long, now: Long): Boolean {
        val lateness = (now - planTime).coerceAtLeast(0L)
        if (lateness <= ON_TIME_GRACE_MS) return true
        if (!task.sendOnTimeout) return false
        return task.repeatType != ScheduledTaskStore.REPEAT_NONE || lateness < SINGLE_TIMEOUT_WINDOW_MS
    }

    private suspend fun execute(taskId: String, occurrence: Long) {
        val task = ScheduledTaskStore.findTask(taskId) ?: return
        task.status = ScheduledTaskStore.STATUS_RUNNING
        ScheduledTaskStore.saveTasks()
        val wakeLock = acquireWakeLock()
        val result = try {
            runCatching { sendTask(task) }.getOrElse {
                WeLogger.e(TAG, "scheduled task failed: ${task.id}", it)
                0 to expectedTargets(task)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
        advance(taskId, occurrence, result.first, result.second, executed = true)
        WeLogger.i(TAG, "scheduled task ${task.id} done ${result.first}/${result.first + result.second}")
    }

    private fun advance(taskId: String, occurrence: Long, success: Int, fail: Int, executed: Boolean) {
        val latest = ScheduledTaskStore.findTask(taskId) ?: return
        val planTimes = latest.sortedPlanTimes
        if (occurrence !in planTimes) {
            latest.status = ScheduledTaskStore.STATUS_PENDING
            ScheduledTaskStore.saveTasks()
            return
        }
        if (latest.repeatType == ScheduledTaskStore.REPEAT_NONE) {
            val remaining = planTimes.filterNot { it == occurrence }
            if (remaining.isEmpty()) {
                ScheduledTaskStore.removeTask(taskId)
                return
            }
            latest.planTimes = remaining.toMutableList()
            latest.status = ScheduledTaskStore.STATUS_PENDING
            if (executed) recordResult(latest, success, fail)
            ScheduledTaskStore.saveTasks()
            return
        }
        val next = ScheduledTaskStore.resolveNextPlanTime(
            occurrence,
            latest.repeatType,
            latest.repeatDays,
            System.currentTimeMillis(),
        )
        if (next <= 0L) {
            ScheduledTaskStore.removeTask(taskId)
            return
        }
        latest.planTimes = planTimes.map { if (it == occurrence) next else it }.toMutableList()
        latest.status = ScheduledTaskStore.STATUS_PENDING
        if (executed) recordResult(latest, success, fail)
        ScheduledTaskStore.saveTasks()
    }

    private fun recordResult(task: ScheduledTask, success: Int, fail: Int) {
        task.lastExecutedTime = System.currentTimeMillis()
        task.lastSuccessCount = success
        task.lastFailCount = fail
    }

    // ---------------- 发送 ----------------

    private suspend fun sendTask(task: ScheduledTask): Pair<Int, Int> {
        if (task.targetType == ScheduledTaskStore.TARGET_MOMENTS) {
            return if (sendMomentsTask(task)) 1 to 0 else 0 to 1
        }
        val items = ScheduledTaskStore.normalizedItems(task)
        if (items.isEmpty()) return 0 to expectedTargets(task)
        val targets = task.targetIds.filter { it.isNotBlank() }
        if (targets.isEmpty()) return 0 to 1
        val displayNames = buildDisplayNameMap()
        var success = 0
        var fail = 0
        targets.forEachIndexed { index, talker ->
            val ok = sendToTalker(task, talker, items, displayNames[talker] ?: talker)
            if (ok) success++ else fail++
            if (index < targets.lastIndex && task.intervalSeconds > 0) {
                delay(task.intervalSeconds * 1000L)
            }
        }
        return success to fail
    }

    private suspend fun sendToTalker(
        task: ScheduledTask,
        talker: String,
        items: List<ScheduledContentItem>,
        displayName: String,
    ): Boolean {
        items.forEachIndexed { index, item ->
            if (!sendItem(item, talker, displayName)) return false
            if (index < items.lastIndex && task.mediaIntervalSeconds > 0) {
                delay(task.mediaIntervalSeconds * 1000L)
            }
        }
        return true
    }

    private fun sendItem(item: ScheduledContentItem, talker: String, displayName: String): Boolean {
        return when (item.type) {
            ScheduledTaskStore.TYPE_TEXT -> {
                val content = item.value.replace(FRIEND_NAME_PLACEHOLDER, displayName)
                content.isNotBlank() && WeMessageApi.sendText(talker, content)
            }
            ScheduledTaskStore.TYPE_XML -> {
                val content = item.value.replace(FRIEND_NAME_PLACEHOLDER, displayName)
                content.isNotBlank() && WeMessageApi.sendXmlAppMsg(talker, content)
            }
            ScheduledTaskStore.TYPE_IMAGE -> File(item.value).isFile && WeMessageApi.sendImage(talker, item.value)
            ScheduledTaskStore.TYPE_VIDEO -> File(item.value).isFile && WeMessageApi.sendVideo(talker, item.value)
            ScheduledTaskStore.TYPE_FILE -> {
                val file = File(item.value)
                file.isFile && WeMessageApi.sendFile(talker, item.value, file.name)
            }
            ScheduledTaskStore.TYPE_EMOJI -> File(item.value).isFile && WeMessageApi.sendEmoji(talker, item.value)
            ScheduledTaskStore.TYPE_VOICE -> File(item.value).isFile &&
                WeMessageApi.sendVoice(talker, item.value, voiceDurationMs(item.value))
            else -> false
        }
    }

    private fun sendMomentsTask(task: ScheduledTask): Boolean {
        ScheduledTaskStore.momentsValidationError(task)?.let {
            WeLogger.w(TAG, "invalid moments task: ${task.id}")
            return false
        }
        val items = ScheduledTaskStore.normalizedMomentsItems(task)
        val content = items.firstOrNull { it.type == ScheduledTaskStore.TYPE_TEXT }?.value.orEmpty()
        val images = items.filter { it.type == ScheduledTaskStore.TYPE_IMAGE }.map { it.value }
        val video = items.firstOrNull { it.type == ScheduledTaskStore.TYPE_VIDEO }?.value.orEmpty()
        val context = HostInfo.application
        return runCatching {
            when (task.momentsType) {
                ScheduledTaskStore.MOMENTS_TEXT -> WeMomentsApi.postText(content)
                ScheduledTaskStore.MOMENTS_TEXT_IMAGE -> WeMomentsApi.postTextAndImages(content, images)
                ScheduledTaskStore.MOMENTS_TEXT_VIDEO -> postMomentVideo(context, content, video)
                ScheduledTaskStore.MOMENTS_IMAGE -> WeMomentsApi.postTextAndImages("", images)
                ScheduledTaskStore.MOMENTS_VIDEO -> postMomentVideo(context, "", video)
                else -> false
            }
        }.getOrElse {
            WeLogger.e(TAG, "moments send failed: ${task.id}", it)
            false
        }
    }

    private fun postMomentVideo(context: android.content.Context, text: String, video: String): Boolean {
        val thumb = WeMomentsApi.generateVideoThumbForUpload(context, video).orEmpty()
        if (thumb.isBlank()) return false
        return WeMomentsApi.postTextAndVideo(context, text, video, thumb)
    }

    // ---------------- 辅助 ----------------

    private fun expectedTargets(task: ScheduledTask): Int =
        if (task.targetType == ScheduledTaskStore.TARGET_MOMENTS) {
            1
        } else {
            task.targetIds.count { it.isNotBlank() }.coerceAtLeast(1)
        }

    private fun buildDisplayNameMap(): Map<String, String> = runCatching {
        val map = HashMap<String, String>()
        WeDatabaseApi.getFriends().forEach { map[it.wxId] = it.displayName }
        WeDatabaseApi.getGroups().forEach { map[it.wxId] = it.displayName }
        map
    }.getOrElse {
        WeLogger.w(TAG, "failed to build display name map")
        emptyMap()
    }

    private fun voiceDurationMs(path: String): Int = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 1000
        } finally {
            retriever.release()
        }
    }.getOrDefault(1000)

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        val powerManager = runCatching {
            HostInfo.application.getSystemService(PowerManager::class.java)
        }.getOrNull() ?: return null
        return runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrElse {
            WeLogger.e(TAG, "acquire wake lock failed", it)
            null
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
            .onFailure { WeLogger.e(TAG, "release wake lock failed", it) }
    }
}
