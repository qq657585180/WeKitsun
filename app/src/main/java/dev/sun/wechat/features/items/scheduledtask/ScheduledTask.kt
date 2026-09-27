package dev.sun.wechat.features.items.scheduledtask

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.sun.wechat.R
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds

/**
 * 定时任务：按计划时间向群/好友发送聊天内容，或发布朋友圈。
 *
 * 任务在 [ScheduledTaskActivity] 中管理；启用后 [ScheduledTaskRuntime] 在微信主进程存活期间调度发送。
 */
object ScheduledTask : ClickableFeature() {

    override val technicalId = "定时任务"
    override val nameRes = R.string.feature_scheduled_task_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_scheduled_task_description

    override fun onEnable() {
        ScheduledTaskRuntime.startScheduler()
    }

    override fun onDisable() {
        ScheduledTaskRuntime.stopScheduler()
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, ScheduledTaskActivity::class.java))
    }
}
