package dev.ujhhgtg.wekit.features.core

import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时功能生效上报。
 *
 * hook 安装成功(即 [BaseFeature.enable] 不抛异常)只代表代码路径挂上了,很多 UI 类
 * 功能(悬浮 FAB/侧边栏/悬浮底栏/头像等)是在 Activity resume 时才异步注入视图的,
 * 安装时无从得知真实效果。各功能在「视图真的注入成功/失败的那一刻」调用
 * [report] 上报运行时状态,设置页的诊断入口据此呈现「功能现在到底有没有生效」,
 * 避免排查「功能开了但没反应」时只能靠猜。
 */
object FeatureRuntimeReporter {

    private const val TAG = "FeatureRuntimeReporter"

    enum class Status { OK, PARTIAL }

    data class Record(
        val technicalId: String,
        val status: Status,
        val detail: String = "",
        val atMs: Long = System.currentTimeMillis(),
    )

    private val records = ConcurrentHashMap<String, Record>()

    /**
     * 上报一次运行时生效状态。同一 [technicalId] 后上报覆盖先上报。
     *
     * @param ok 视图/功能是否真实注入并生效;不生效时 [detail] 应说明原因
     *        (如具体失败方法名、注入计数为 0)。
     */
    fun report(technicalId: String, ok: Boolean, detail: String = "") {
        val status = if (ok) Status.OK else Status.PARTIAL
        records[technicalId] = Record(technicalId, status, detail)
        WeLogger.i(TAG, "runtime $technicalId -> ${status.name}${if (detail.isBlank()) "" else " · $detail"}")
    }

    /** 运行时状态快照(逐功能,按键排序)。 */
    fun snapshot(): List<Record> = records.values.sortedBy { it.technicalId }

    /** 是否有未真实生效的项。 */
    fun hasIssues(): Boolean = records.values.any { it.status != Status.OK }

    fun issueCount(): Int = records.values.count { it.status != Status.OK }

    fun clear() {
        records.clear()
    }
}