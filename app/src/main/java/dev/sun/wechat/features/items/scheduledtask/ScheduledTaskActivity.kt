package dev.sun.wechat.features.items.scheduledtask

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Save
import dev.sun.wechat.R
import dev.sun.wechat.features.api.core.WeDatabaseApi
import dev.sun.wechat.features.api.core.models.IWeContact
import dev.sun.wechat.i18n.LocaleResourceMode
import dev.sun.wechat.i18n.WeKitLocaleProvider
import dev.sun.wechat.ui.agent.settings.AgentActionRow
import dev.sun.wechat.ui.agent.settings.AgentConfirmDialog
import dev.sun.wechat.ui.agent.settings.AgentListActionButton
import dev.sun.wechat.ui.agent.settings.AgentSettingsScaffold
import dev.sun.wechat.ui.animation.predictiveback.weKitNavTransition
import dev.sun.wechat.ui.content.ContactsSelector
import dev.sun.wechat.ui.content.WeDateTimeField
import dev.sun.wechat.ui.content.WeDateTimeMode
import dev.sun.wechat.ui.content.formatDateTime
import dev.sun.wechat.ui.content.m3.BaseWidget
import dev.sun.wechat.ui.content.m3.IntNumberPickerWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.content.m3.TextFieldDialogWidget
import dev.sun.wechat.ui.content.parseDateTime
import dev.sun.wechat.ui.navigation.LocalNavigator
import dev.sun.wechat.ui.navigation.Navigator
import dev.sun.wechat.ui.navigation.rememberM3NavEffects
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.ui.utils.theme.ModuleTheme
import dev.sun.wechat.ui.utils.theme.ThemeSettings
import dev.sun.wechat.utils.android.showToast
import dev.sun.wechat.utils.fs.KnownPaths
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlin.io.path.div
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@Keep
class ScheduledTaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme { ScheduledTaskRoot(::finish) }
            }
        }
    }
}

@Serializable
private sealed interface ScheduledTaskRoute : NavKey {
    @Serializable data object Home : ScheduledTaskRoute
    @Serializable data class Editor(val taskId: String?) : ScheduledTaskRoute
}

@Composable
private fun ScheduledTaskRoot(onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<ScheduledTaskRoute>(ScheduledTaskRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() },
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<ScheduledTaskRoute.Home> {
                ScheduledTaskHomeScreen(
                    onBack = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() },
                    onEdit = { navigator.push(ScheduledTaskRoute.Editor(it)) },
                )
            }
            entry<ScheduledTaskRoute.Editor>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                ScheduledTaskEditorScreen(route.taskId, navigator::pop)
            }
        }
    }
}

@Composable
private fun ScheduledTaskHomeScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val tasks = ScheduledTaskStore.tasks
    LaunchedEffect(Unit) { ScheduledTaskStore.reload() }
    AgentSettingsScaffold(stringResource(R.string.feature_scheduled_task_name), onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.scheduled_task_section_tasks)) {
                item {
                    BaseWidget(
                        title = stringResource(R.string.scheduled_task_new),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = { onEdit(null) },
                    )
                }
                if (tasks.isEmpty()) {
                    item { BaseWidget(title = stringResource(R.string.scheduled_task_empty)) }
                } else {
                    tasks.forEach { task ->
                        item(key = task.id) {
                            SwitchWidget(
                                title = taskTitle(task),
                                description = taskDescription(task),
                                checked = task.enabled,
                                trailingDivider = true,
                                onClick = { onEdit(task.id) },
                                onCheckedChange = { enabled ->
                                    task.enabled = enabled
                                    ScheduledTaskStore.saveTasks()
                                    ScheduledTaskStore.reload()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskEditorScreen(taskId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creating = taskId == null
    val initial = remember(taskId) {
        taskId?.let { ScheduledTaskStore.findTask(it) } ?: ScheduledTaskStore.newDraft()
    }

    var remark by remember { mutableStateOf(initial.remark) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var targetType by remember { mutableStateOf(initial.targetType) }
    var contentType by remember {
        mutableStateOf(initial.items.firstOrNull()?.type ?: ScheduledTaskStore.TYPE_TEXT)
    }
    var momentsType by remember { mutableStateOf(initial.momentsType) }
    var text by remember {
        mutableStateOf(initial.items.firstOrNull { it.type == ScheduledTaskStore.TYPE_TEXT }?.value ?: "")
    }
    var mediaPaths by remember {
        mutableStateOf(initial.items.filter { it.type != ScheduledTaskStore.TYPE_TEXT }.map { it.value })
    }
    var targetIds by remember { mutableStateOf(initial.targetIds.toSet()) }
    var planTexts by remember {
        mutableStateOf(
            initial.sortedPlanTimes.map { formatDateTime(it) }
                .ifEmpty { listOf(formatDateTime(ScheduledTaskStore.newDraft().firstPlanTime)) },
        )
    }
    var repeatType by remember { mutableStateOf(initial.repeatType) }
    var repeatDays by remember { mutableStateOf(initial.repeatDays.toSet()) }
    var intervalSeconds by remember { mutableStateOf(initial.intervalSeconds) }
    var mediaIntervalSeconds by remember { mutableStateOf(initial.mediaIntervalSeconds) }
    var sendOnTimeout by remember { mutableStateOf(initial.sendOnTimeout) }
    var pendingDelete by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        }
    }

    fun copyToModule(uri: Uri): String? {
        val dir = (KnownPaths.moduleRoot / "scheduled_task").toFile().apply { mkdirs() }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "media_${System.currentTimeMillis()}"
        val target = File(dir, "${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return target.absolutePath
    }

    fun mediaMime(): String = when {
        targetType == ScheduledTaskStore.TARGET_MOMENTS &&
            (momentsType == ScheduledTaskStore.MOMENTS_TEXT_VIDEO || momentsType == ScheduledTaskStore.MOMENTS_VIDEO) -> "video/*"
        targetType == ScheduledTaskStore.TARGET_MOMENTS -> "image/*"
        contentType == ScheduledTaskStore.TYPE_VIDEO -> "video/*"
        contentType == ScheduledTaskStore.TYPE_IMAGE -> "image/*"
        contentType == ScheduledTaskStore.TYPE_VOICE -> "audio/*"
        else -> "*/*"
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        copyToModule(uri)?.let { mediaPaths = mediaPaths + it }
    }

    fun buildItems(): List<ScheduledContentItem> {
        val body = text.trim()
        val textItem = body.takeIf { it.isNotBlank() }?.let { ScheduledContentItem(ScheduledTaskStore.TYPE_TEXT, it) }
        return if (targetType == ScheduledTaskStore.TARGET_MOMENTS) {
            when (momentsType) {
                ScheduledTaskStore.MOMENTS_TEXT -> listOfNotNull(textItem)
                ScheduledTaskStore.MOMENTS_TEXT_IMAGE ->
                    listOfNotNull(textItem) + mediaPaths.map { ScheduledContentItem(ScheduledTaskStore.TYPE_IMAGE, it) }
                ScheduledTaskStore.MOMENTS_TEXT_VIDEO ->
                    listOfNotNull(textItem) + mediaPaths.take(1).map { ScheduledContentItem(ScheduledTaskStore.TYPE_VIDEO, it) }
                ScheduledTaskStore.MOMENTS_IMAGE ->
                    mediaPaths.map { ScheduledContentItem(ScheduledTaskStore.TYPE_IMAGE, it) }
                ScheduledTaskStore.MOMENTS_VIDEO ->
                    mediaPaths.take(1).map { ScheduledContentItem(ScheduledTaskStore.TYPE_VIDEO, it) }
                else -> emptyList()
            }
        } else {
            if (ScheduledTaskStore.scheduledTypeUsesText(contentType)) {
                listOf(ScheduledContentItem(contentType, body))
            } else {
                mediaPaths.map { ScheduledContentItem(contentType, it) }
            }
        }
    }

    fun validate(): Int? {
        if (planTexts.mapNotNull { parseDateTime(it) }.isEmpty()) {
            return R.string.scheduled_task_need_time
        }
        if (targetType == ScheduledTaskStore.TARGET_CHAT) {
            if (targetIds.isEmpty()) return R.string.scheduled_task_need_targets
            if (buildItems().all { it.value.isBlank() }) return R.string.scheduled_task_need_content
        } else {
            val probe = initial.copy(
                targetType = ScheduledTaskStore.TARGET_MOMENTS,
                momentsType = momentsType,
                items = buildItems().toMutableList(),
            )
            ScheduledTaskStore.momentsValidationError(probe)?.let { return it }
        }
        return null
    }

    fun save(): Boolean {
        val error = validate()
        if (error != null) {
            showToast(context, context.getString(error))
            return false
        }
        val planTimes = planTexts.mapNotNull { parseDateTime(it) }.distinct().sorted()
        val task = initial.copy(
            remark = remark.trim(),
            enabled = enabled,
            targetType = targetType,
            momentsType = momentsType,
            items = buildItems().toMutableList(),
            targetIds = (if (targetType == ScheduledTaskStore.TARGET_CHAT) targetIds.toList() else emptyList()).toMutableList(),
            planTimes = planTimes.toMutableList(),
            repeatType = repeatType,
            repeatDays = repeatDays.toMutableSet(),
            intervalSeconds = intervalSeconds,
            mediaIntervalSeconds = mediaIntervalSeconds,
            sendOnTimeout = sendOnTimeout,
            status = ScheduledTaskStore.STATUS_PENDING,
        )
        ScheduledTaskStore.upsertTask(task)
        ScheduledTaskRuntime.startScheduler()
        showToast(context, context.getString(R.string.scheduled_task_saved))
        return true
    }

    val confirmLabel = stringResource(R.string.dialog_confirm)
    val cancelLabel = stringResource(R.string.dialog_cancel)
    val chatTypes = listOf(
        ScheduledTaskStore.TYPE_TEXT to stringResource(R.string.scheduled_task_type_text),
        ScheduledTaskStore.TYPE_XML to stringResource(R.string.scheduled_task_type_xml),
        ScheduledTaskStore.TYPE_IMAGE to stringResource(R.string.scheduled_task_type_image),
        ScheduledTaskStore.TYPE_VIDEO to stringResource(R.string.scheduled_task_type_video),
        ScheduledTaskStore.TYPE_FILE to stringResource(R.string.scheduled_task_type_file),
        ScheduledTaskStore.TYPE_EMOJI to stringResource(R.string.scheduled_task_type_emoji),
        ScheduledTaskStore.TYPE_VOICE to stringResource(R.string.scheduled_task_type_voice),
    )
    val momentsTypes = listOf(
        ScheduledTaskStore.MOMENTS_TEXT to stringResource(R.string.scheduled_task_moments_type_text),
        ScheduledTaskStore.MOMENTS_TEXT_IMAGE to stringResource(R.string.scheduled_task_moments_type_text_image),
        ScheduledTaskStore.MOMENTS_TEXT_VIDEO to stringResource(R.string.scheduled_task_moments_type_text_video),
        ScheduledTaskStore.MOMENTS_IMAGE to stringResource(R.string.scheduled_task_moments_type_image),
        ScheduledTaskStore.MOMENTS_VIDEO to stringResource(R.string.scheduled_task_moments_type_video),
    )
    val repeatTypes = listOf(
        ScheduledTaskStore.REPEAT_NONE to stringResource(R.string.scheduled_task_repeat_none),
        ScheduledTaskStore.REPEAT_DAILY to stringResource(R.string.scheduled_task_repeat_daily),
        ScheduledTaskStore.REPEAT_WEEKLY to stringResource(R.string.scheduled_task_repeat_weekly),
    )
    val weekdaySymbols = remember { DateFormatSymbols.getInstance().shortWeekdays }

    AgentSettingsScaffold(
        title = if (creating) stringResource(R.string.scheduled_task_new) else stringResource(R.string.scheduled_task_edit),
        onBack = onBack,
    ) {
        item {
            SegmentedColumn(title = stringResource(R.string.scheduled_task_section_basic)) {
                item {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.scheduled_task_remark),
                        value = remark,
                        onValueChange = { remark = it },
                        dialogTitle = stringResource(R.string.scheduled_task_remark),
                        confirmLabel = confirmLabel,
                        dismissLabel = cancelLabel,
                        valueHint = stringResource(R.string.scheduled_task_remark_hint),
                    )
                }
                item {
                    SwitchWidget(
                        title = stringResource(R.string.scheduled_task_enabled),
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.scheduled_task_section_target)) {
                item {
                    ChipRow(
                        selected = targetType,
                        options = listOf(
                            ScheduledTaskStore.TARGET_CHAT to stringResource(R.string.scheduled_task_target_chat),
                            ScheduledTaskStore.TARGET_MOMENTS to stringResource(R.string.scheduled_task_target_moments),
                        ),
                        onSelect = { targetType = it },
                    )
                }
                if (targetType == ScheduledTaskStore.TARGET_CHAT) {
                    item {
                        val pickLabel = stringResource(R.string.scheduled_task_pick_targets)
                        BaseWidget(
                            title = pickLabel,
                            description = stringResource(R.string.scheduled_task_targets_count, targetIds.size),
                            onClick = {
                                showComposeDialog(context) {
                                    val close = onDismiss
                                    ContactsSelector(
                                        title = pickLabel,
                                        contacts = contacts,
                                        initialSelectedWxIds = targetIds,
                                        onDismiss = close,
                                        onConfirm = { picked ->
                                            targetIds = picked
                                            close()
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.scheduled_task_section_content)) {
                if (targetType == ScheduledTaskStore.TARGET_CHAT) {
                    item {
                        ChipRow(
                            selected = contentType,
                            options = chatTypes,
                            onSelect = {
                                contentType = it
                                mediaPaths = emptyList()
                            },
                        )
                    }
                    if (ScheduledTaskStore.scheduledTypeUsesText(contentType)) {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.scheduled_task_content),
                                value = text,
                                onValueChange = { text = it },
                                dialogTitle = stringResource(R.string.scheduled_task_content),
                                confirmLabel = confirmLabel,
                                dismissLabel = cancelLabel,
                                valueHint = stringResource(R.string.scheduled_task_content_hint),
                                singleLine = false,
                            )
                        }
                    } else {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.scheduled_task_add_media),
                                icon = MaterialSymbols.Outlined.Add,
                                onClick = { mediaPicker.launch(arrayOf(mediaMime())) },
                            )
                        }
                        mediaPaths.forEachIndexed { index, path ->
                            item(key = "$index:$path") {
                                BaseWidget(
                                    title = File(path).name,
                                    description = path,
                                    onClick = { mediaPaths = mediaPaths.filterIndexed { i, _ -> i != index } },
                                )
                            }
                        }
                    }
                } else {
                    item {
                        ChipRow(
                            selected = momentsType,
                            options = momentsTypes,
                            onSelect = {
                                momentsType = it
                                mediaPaths = emptyList()
                            },
                        )
                    }
                    if (momentsType in listOf(
                            ScheduledTaskStore.MOMENTS_TEXT,
                            ScheduledTaskStore.MOMENTS_TEXT_IMAGE,
                            ScheduledTaskStore.MOMENTS_TEXT_VIDEO,
                        )
                    ) {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.scheduled_task_content),
                                value = text,
                                onValueChange = { text = it },
                                dialogTitle = stringResource(R.string.scheduled_task_content),
                                confirmLabel = confirmLabel,
                                dismissLabel = cancelLabel,
                                valueHint = stringResource(R.string.scheduled_task_moments_content_hint),
                                singleLine = false,
                            )
                        }
                    }
                    val wantsImage = momentsType == ScheduledTaskStore.MOMENTS_TEXT_IMAGE ||
                        momentsType == ScheduledTaskStore.MOMENTS_IMAGE
                    val wantsVideo = momentsType == ScheduledTaskStore.MOMENTS_TEXT_VIDEO ||
                        momentsType == ScheduledTaskStore.MOMENTS_VIDEO
                    if (wantsImage || wantsVideo) {
                        item {
                            BaseWidget(
                                title = stringResource(
                                    if (wantsVideo) R.string.scheduled_task_add_video else R.string.scheduled_task_add_images,
                                ),
                                icon = MaterialSymbols.Outlined.Add,
                                onClick = { mediaPicker.launch(arrayOf(mediaMime())) },
                            )
                        }
                        mediaPaths.forEachIndexed { index, path ->
                            item(key = "$index:$path") {
                                BaseWidget(
                                    title = File(path).name,
                                    description = path,
                                    onClick = { mediaPaths = mediaPaths.filterIndexed { i, _ -> i != index } },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.scheduled_task_section_schedule)) {
                item {
                    ChipRow(selected = repeatType, options = repeatTypes, onSelect = { repeatType = it })
                }
                if (repeatType == ScheduledTaskStore.REPEAT_WEEKLY) {
                    item {
                        ChipRow(
                            selected = -1,
                            options = ScheduledTaskStore.validRepeatDays()
                                .sorted()
                                .map { day -> day to (weekdaySymbols.getOrNull(day) ?: "") },
                            onSelect = { day ->
                                repeatDays = if (day in repeatDays) repeatDays - day else repeatDays + day
                            },
                        )
                    }
                }
                planTexts.forEachIndexed { index, value ->
                    item(key = "plan:$index") {
                        WeDateTimeField(
                            value = value,
                            onValueChange = { updated ->
                                planTexts = planTexts.toMutableList().also { it[index] = updated }
                            },
                            label = stringResource(R.string.scheduled_task_plan_time),
                            mode = WeDateTimeMode.DATE_TIME,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (planTexts.size > 1) {
                        item(key = "plan_del:$index") {
                            BaseWidget(
                                title = stringResource(R.string.scheduled_task_remove_time),
                                icon = MaterialSymbols.Outlined.Delete,
                                onClick = { planTexts = planTexts.filterIndexed { i, _ -> i != index } },
                            )
                        }
                    }
                }
                item {
                    BaseWidget(
                        title = stringResource(R.string.scheduled_task_add_time),
                        icon = MaterialSymbols.Outlined.Add,
                        onClick = {
                            planTexts = planTexts + formatDateTime(System.currentTimeMillis() + 3_600_000L)
                        },
                    )
                }
                item {
                    IntNumberPickerWidget(
                        title = stringResource(R.string.scheduled_task_interval),
                        value = intervalSeconds,
                        startInt = 0,
                        endInt = 3600,
                        stepSize = 5,
                        valueSuffix = " s",
                        onValueChange = { intervalSeconds = it },
                    )
                }
                item {
                    IntNumberPickerWidget(
                        title = stringResource(R.string.scheduled_task_media_interval),
                        value = mediaIntervalSeconds,
                        startInt = 0,
                        endInt = 3600,
                        stepSize = 5,
                        valueSuffix = " s",
                        onValueChange = { mediaIntervalSeconds = it },
                    )
                }
                item {
                    SwitchWidget(
                        title = stringResource(R.string.scheduled_task_send_on_timeout),
                        checked = sendOnTimeout,
                        onCheckedChange = { sendOnTimeout = it },
                    )
                }
                if (!creating) {
                    item {
                        BaseWidget(
                            title = stringResource(R.string.scheduled_task_run_now),
                            onClick = {
                                if (save()) scope.launch { ScheduledTaskRuntime.runNow(initial.id) }
                            },
                        )
                    }
                }
            }
        }

        if (!creating) {
            item {
                SegmentedColumn(title = stringResource(R.string.scheduled_task_section_danger)) {
                    item {
                        BaseWidget(
                            title = stringResource(R.string.scheduled_task_delete),
                            icon = MaterialSymbols.Outlined.Delete,
                            isError = true,
                            onClick = { pendingDelete = true },
                        )
                    }
                }
            }
        }

        item {
            AgentActionRow {
                AgentListActionButton(
                    label = stringResource(R.string.action_save),
                    icon = MaterialSymbols.Outlined.Save,
                    onClick = { if (save()) onBack() },
                )
            }
        }
    }

    AgentConfirmDialog(
        show = pendingDelete,
        title = stringResource(R.string.scheduled_task_delete),
        message = stringResource(R.string.scheduled_task_delete_confirm),
        confirmLabel = stringResource(R.string.scheduled_task_delete),
        dismissLabel = cancelLabel,
        destructive = true,
        onConfirm = {
            pendingDelete = false
            ScheduledTaskStore.removeTask(initial.id)
            onBack()
        },
        onDismiss = { pendingDelete = false },
    )
}

@Composable
private fun ChipRow(
    selected: Int,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun taskTitle(task: ScheduledTask): String {
    if (task.remark.isNotBlank()) return task.remark
    return when {
        task.targetType == ScheduledTaskStore.TARGET_MOMENTS ->
            stringResource(R.string.scheduled_task_fallback_moments)
        task.items.isNotEmpty() -> task.items.first().value.take(20)
        else -> stringResource(R.string.scheduled_task_fallback_task)
    }
}

@Composable
private fun taskDescription(task: ScheduledTask): String {
    val times = task.sortedPlanTimes.joinToString(" ") { formatDateTime(it) }
    val repeat = when (task.repeatType) {
        ScheduledTaskStore.REPEAT_DAILY -> stringResource(R.string.scheduled_task_repeat_daily)
        ScheduledTaskStore.REPEAT_WEEKLY -> stringResource(R.string.scheduled_task_repeat_weekly)
        else -> stringResource(R.string.scheduled_task_repeat_none)
    }
    val target = if (task.targetType == ScheduledTaskStore.TARGET_MOMENTS) {
        stringResource(R.string.scheduled_task_fallback_moments)
    } else {
        stringResource(R.string.scheduled_task_targets_short, task.targetIds.size)
    }
    return listOf(times, repeat, target).filter { it.isNotBlank() }.joinToString(" · ")
}
