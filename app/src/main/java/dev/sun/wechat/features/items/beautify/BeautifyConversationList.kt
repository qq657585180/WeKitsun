package dev.sun.wechat.features.items.beautify

import android.content.Context
import android.content.res.ColorStateList
import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sun.wechat.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.sun.wechat.features.api.core.WeConversationApi
import dev.sun.wechat.features.api.ui.WeConversationListViewApi
import dev.sun.wechat.features.core.ClickableFeature
import dev.sun.wechat.features.core.FeatureCategoryIds
import dev.sun.wechat.data.KvStore.prefOption
import dev.sun.wechat.ui.content.AlertDialogContent
import dev.sun.wechat.ui.content.TextButton
import dev.sun.wechat.ui.content.m3.DropDownMenuWidget
import dev.sun.wechat.ui.content.m3.DropdownOption
import dev.sun.wechat.ui.content.m3.IntNumberPickerWidget
import dev.sun.wechat.ui.content.m3.SegmentedColumn
import dev.sun.wechat.ui.content.m3.SwitchWidget
import dev.sun.wechat.ui.utils.dpToPx
import dev.sun.wechat.ui.utils.showComposeDialog
import dev.sun.wechat.utils.WeLogger
import dev.sun.wechat.utils.android.getTopMostActivity
import dev.sun.wechat.utils.android.isDarkMode
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    NO_LAYOUT(0, 0, 0, 0, 0),
    PINNED_GROUPED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
}

object BeautifyConversationList : ClickableFeature() {

    override val technicalId = "美化对话列表"
    override val nameRes = R.string.feature_beautify_conversation_list_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_conversation_list_description

    private const val TAG = "BeautifyConversationList"

    private var presetName by prefOption(
        "beautify_conversation_list_preset",
        ConversationListPreset.NO_LAYOUT.name,
    )
    private var highlightUnreadEnabled by prefOption("beautify_conversation_list_highlight_unread", false)
    private var hideDividersEnabled by prefOption("beautify_conversation_list_hide_dividers", false)

    private val selectedPreset: ConversationListPreset
        get() = ConversationListPreset.entries.firstOrNull { it.name == presetName }
            ?: ConversationListPreset.NO_LAYOUT

    private enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

    private data class RowBackgroundKey(
        val preset: ConversationListPreset,
        val unread: Boolean,
        val isDark: Boolean,
        val density: Float,
        val groupPosition: GroupPosition,
    )

    private data class RowVisualState(
        var baselineBackground: Drawable?,
        var baselinePaddingLeft: Int,
        var baselinePaddingTop: Int,
        var baselinePaddingRight: Int,
        var baselinePaddingBottom: Int,
        var moduleBackground: Drawable? = null,
        var backgroundKey: RowBackgroundKey? = null,
    )

    private sealed interface UnreadAccessor {
        data class Field(val get: (Any) -> Any?) : UnreadAccessor
        data object Missing : UnreadAccessor
    }

    private val rowStates = WeakHashMap<View, RowVisualState>()
    private val unreadAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val unreadFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val usernameAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val usernameFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()

    // ── 全局列表卡片化（lexin 风格）──
    private var globalCardsEnabled by prefOption("beautify_global_list_cards_enabled", false)
    private var globalCardsAlpha by prefOption("beautify_global_list_cards_alpha", 200)
    private var globalCardsRadius by prefOption("beautify_global_list_cards_radius", 16)
    private var globalCardsInset by prefOption("beautify_global_list_cards_inset", 10)
    private var globalCardsGap by prefOption("beautify_global_list_cards_gap", 8)
    private var globalCardsHeight by prefOption("beautify_global_list_cards_height", 0)
    private var globalCardsDark by prefOption("beautify_global_list_cards_dark", false)
    private var globalHooksRegistered = false
    private val globalFillsStripped = WeakHashMap<View, Boolean>()
    private val globalOrigHeights = WeakHashMap<View, Int>()
    private val globalCardDrawables = WeakHashMap<View, GlobalCardDrawable>()
    private val globalChattingCache = WeakHashMap<View, Boolean>()
    private val globalListCache = WeakHashMap<View, Boolean>()
    private val globalPendingLists = WeakHashMap<ViewGroup, Boolean>()

    /** lexin 风格自绘卡片：颜色/圆角在创建时烘焙好，滚动时零测量 */
    private class GlobalCardDrawable(
        private val colour: Int,
        private val corner: Float,
        private val alpha: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        fun matches(cornerPx: Float, alphaValue: Int): Boolean =
            abs(this.corner - cornerPx) < 0.01f && this.alpha == alphaValue

        override fun draw(canvas: Canvas) {
            try {
                rect.set(bounds)
                clip.reset()
                clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                paint.color = colour
                paint.alpha = alpha
                canvas.drawRect(rect, paint)
                canvas.restore()
            } catch (ignored: Throwable) {
                // 仅外观
            }
        }

        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation, context ->
        applyRowVisuals(row, conversation, context)
    }

    override fun onEnable() {
        WeConversationListViewApi.addListener(bindListener)
        registerGlobalHooks()
        updateDividerRequest()
        applyGlobalCardsNow()
        WeConversationListViewApi.refresh()
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(bindListener)
        WeConversationListViewApi.removeDividerOwner(this)
        globalHooksRegistered = false
        rowStates.clear()
        unreadAccessorCache.clear()
        usernameAccessorCache.clear()
        unreadFailuresLogged.clear()
        usernameFailuresLogged.clear()
        restoreGlobalCards()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var preset by remember { mutableStateOf(selectedPreset) }
            var highlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
            var hideDividers by remember { mutableStateOf(hideDividersEnabled) }

            var globalCardsEnabled by remember { mutableStateOf(this@BeautifyConversationList.globalCardsEnabled) }
            var globalCardsDark by remember { mutableStateOf(this@BeautifyConversationList.globalCardsDark) }
            var globalCardsAlpha by remember { mutableStateOf(this@BeautifyConversationList.globalCardsAlpha) }
            var globalCardsRadius by remember { mutableStateOf(this@BeautifyConversationList.globalCardsRadius) }
            var globalCardsInset by remember { mutableStateOf(this@BeautifyConversationList.globalCardsInset) }
            var globalCardsGap by remember { mutableStateOf(this@BeautifyConversationList.globalCardsGap) }
            var globalCardsHeight by remember { mutableStateOf(this@BeautifyConversationList.globalCardsHeight) }

            fun applyGlobalCardsSettings() {
                if (globalCardsEnabled) applyGlobalCardsNow() else restoreGlobalCards()
            }

            fun applyChanges(highlight: Boolean, dividers: Boolean) {
                highlightUnreadEnabled = highlight
                hideDividersEnabled = dividers
                updateDividerRequest()
                WeConversationListViewApi.refresh()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_conversation_list_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item(key = "preset") {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_preset),
                                description = null,
                                value = preset,
                                options = ConversationListPreset.entries.map { entry ->
                                    DropdownOption(
                                        entry,
                                        when (entry) {
                                            ConversationListPreset.NO_LAYOUT -> stringResource(R.string.beautify_conversation_no_layout)
                                            ConversationListPreset.PINNED_GROUPED_CARD -> stringResource(R.string.beautify_conversation_pinned_grouped)
                                        },
                                    )
                                },
                                onValueChange = { entry ->
                                    preset = entry
                                    if (entry == ConversationListPreset.NO_LAYOUT) highlightUnread = false
                                    presetName = entry.name
                                    applyChanges(
                                        highlight = entry != ConversationListPreset.NO_LAYOUT && highlightUnread,
                                        dividers = hideDividers,
                                    )
                                },
                            )
                        }
                        item(
                            key = "highlight_unread",
                            animatedVisibility = preset != ConversationListPreset.NO_LAYOUT,
                        ) {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_highlight_unread),
                                checked = highlightUnread,
                                onCheckedChange = {
                                    highlightUnread = it
                                    applyChanges(highlight = it, dividers = hideDividers)
                                },
                            )
                        }
                        item(key = "hide_dividers") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.beautify_conversation_hide_dividers),
                                checked = hideDividers,
                                onCheckedChange = {
                                    hideDividers = it
                                    applyChanges(highlight = highlightUnread, dividers = it)
                                },
                            )
                        }
                        item(key = "global_cards_section") {
                            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                                item {
                                    SwitchWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_enabled),
                                        description = stringResource(R.string.beautify_global_list_cards_summary),
                                        checked = globalCardsEnabled,
                                        onCheckedChange = {
                                            globalCardsEnabled = it
                                            this@BeautifyConversationList.globalCardsEnabled = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_dark", animatedVisibility = globalCardsEnabled) {
                                    SwitchWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_dark),
                                        checked = globalCardsDark,
                                        onCheckedChange = {
                                            globalCardsDark = it
                                            this@BeautifyConversationList.globalCardsDark = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_alpha", animatedVisibility = globalCardsEnabled) {
                                    IntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_alpha),
                                        value = globalCardsAlpha,
                                        startInt = 0,
                                        endInt = 255,
                                        stepSize = 5,
                                        valueSuffix = "/255",
                                        onValueChange = {
                                            globalCardsAlpha = it
                                            this@BeautifyConversationList.globalCardsAlpha = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_radius", animatedVisibility = globalCardsEnabled) {
                                    IntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_radius),
                                        value = globalCardsRadius,
                                        startInt = 0,
                                        endInt = 48,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsRadius = it
                                            this@BeautifyConversationList.globalCardsRadius = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_inset", animatedVisibility = globalCardsEnabled) {
                                    IntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_inset),
                                        value = globalCardsInset,
                                        startInt = 0,
                                        endInt = 60,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsInset = it
                                            this@BeautifyConversationList.globalCardsInset = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_gap", animatedVisibility = globalCardsEnabled) {
                                    IntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_gap),
                                        value = globalCardsGap,
                                        startInt = 0,
                                        endInt = 60,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsGap = it
                                            this@BeautifyConversationList.globalCardsGap = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                                item(key = "global_height", animatedVisibility = globalCardsEnabled) {
                                    IntNumberPickerWidget(
                                        iconPlaceholder = false,
                                        title = stringResource(R.string.beautify_global_list_cards_height),
                                        value = globalCardsHeight,
                                        startInt = -24,
                                        endInt = 48,
                                        stepSize = 2,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            globalCardsHeight = it
                                            this@BeautifyConversationList.globalCardsHeight = it
                                            applyGlobalCardsSettings()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun applyRowVisuals(
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        val state = rowStates.getOrPut(row) {
            RowVisualState(
                baselineBackground = row.background,
                baselinePaddingLeft = row.paddingLeft,
                baselinePaddingTop = row.paddingTop,
                baselinePaddingRight = row.paddingRight,
                baselinePaddingBottom = row.paddingBottom,
            )
        }
        restoreRowBaseline(row, state)

        val preset = selectedPreset
        if (preset == ConversationListPreset.NO_LAYOUT) {
            WeConversationListViewApi.setRowDividerHidden(this, row, false)
            return
        }

        val grouped = preset == ConversationListPreset.PINNED_GROUPED_CARD
        val groupPosition = if (grouped) groupPosition(conversation, context) else GroupPosition.SINGLE
        val pinned = if (grouped) isPinnedConversation(conversation) else false
        val nextPinned = if (grouped) context.nextConversation?.let(::isPinnedConversation) else null
        WeConversationListViewApi.setRowDividerHidden(
            owner = this,
            row = row,
            hidden = grouped && pinned && nextPinned == false,
        )

        val unread = highlightUnreadEnabled && isUnread(conversation)
        val backgroundKey = RowBackgroundKey(
            preset = preset,
            unread = unread,
            isDark = row.context.isDarkMode,
            density = row.resources.displayMetrics.density,
            groupPosition = groupPosition,
        )
        val background = if (state.backgroundKey == backgroundKey) {
            state.moduleBackground!!
        } else {
            buildRowBackground(row.context, preset, unread, groupPosition).also {
                state.backgroundKey = backgroundKey
                state.moduleBackground = it
            }
        }
        row.background = background
        row.setPadding(
            state.baselinePaddingLeft,
            state.baselinePaddingTop,
            state.baselinePaddingRight,
            state.baselinePaddingBottom,
        )
    }

    private fun restoreRowBaseline(row: View, state: RowVisualState) {
        if (row.background === state.moduleBackground) {
            row.background = state.baselineBackground
            row.setPadding(
                state.baselinePaddingLeft,
                state.baselinePaddingTop,
                state.baselinePaddingRight,
                state.baselinePaddingBottom,
            )
        } else {
            state.baselineBackground = row.background
            state.baselinePaddingLeft = row.paddingLeft
            state.baselinePaddingTop = row.paddingTop
            state.baselinePaddingRight = row.paddingRight
            state.baselinePaddingBottom = row.paddingBottom
            state.moduleBackground = null
            state.backgroundKey = null
        }
    }

    private fun buildRowBackground(
        context: Context,
        preset: ConversationListPreset,
        unread: Boolean,
        groupPosition: GroupPosition,
    ): Drawable {
        val isDark = context.isDarkMode
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
                setCornerRadii(cornerRadii(context, preset.rowRadiusDp, groupPosition))
            } else {
                cornerRadius = preset.rowRadiusDp.dpToPx(context).toFloat()
            }
            setColor(
                when {
                    unread && isDark -> 0xFF253E37.toInt()
                    unread -> 0xFFEAF8F2.toInt()
                    isDark -> preset.darkBackgroundColor
                    else -> preset.lightBackgroundColor
                },
            )
            setStroke(1.dpToPx(context).coerceAtLeast(1), if (isDark) 0x22FFFFFF else 0x16161D1C)
        }
        val horizontalInset = preset.horizontalInsetDp.dpToPx(context)
        val verticalInset = preset.verticalInsetDp.dpToPx(context)
        val topInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.FIRST -> verticalInset
                GroupPosition.MIDDLE, GroupPosition.LAST -> 0
            }
        } else {
            verticalInset
        }
        val bottomInset = if (preset == ConversationListPreset.PINNED_GROUPED_CARD) {
            when (groupPosition) {
                GroupPosition.SINGLE, GroupPosition.LAST -> verticalInset
                GroupPosition.FIRST, GroupPosition.MIDDLE -> 0
            }
        } else {
            verticalInset
        }
        val inset = InsetDrawable(card, horizontalInset, topInset, horizontalInset, bottomInset)
        val rippleColor = if (isDark) 0x2AFFFFFF else 0x18006A62
        return RippleDrawable(ColorStateList.valueOf(rippleColor), inset, null)
    }

    private fun cornerRadii(context: Context, radiusDp: Int, position: GroupPosition): FloatArray {
        val radius = radiusDp.dpToPx(context).toFloat()
        val zero = 0f
        return when (position) {
            GroupPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            GroupPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
            GroupPosition.MIDDLE -> floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
            GroupPosition.LAST -> floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
        }
    }

    private fun groupPosition(
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ): GroupPosition {
        val pinned = isPinnedConversation(conversation)
        val previousPinned = context.previousConversation?.let(::isPinnedConversation)
        val nextPinned = context.nextConversation?.let(::isPinnedConversation)
        return when {
            previousPinned != pinned && nextPinned != pinned -> GroupPosition.SINGLE
            previousPinned != pinned -> GroupPosition.FIRST
            nextPinned != pinned -> GroupPosition.LAST
            else -> GroupPosition.MIDDLE
        }
    }

    private fun isPinnedConversation(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = usernameAccessorCache.computeIfAbsent(modelClass, ::findUsernameAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val talker = (accessor as UnreadAccessor.Field).get(conversation) as? String ?: return false
            WeConversationApi.isPinned(talker)
        } catch (error: Exception) {
            logUsernameFailureOnce(modelClass, "could not read field_username", error)
            false
        }
    }

    private fun findUsernameAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_username"
            superclass()
        } ?: run {
            logUsernameFailureOnce(modelClass, "field_username is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUsernameFailureOnce(modelClass, "could not resolve field_username", error)
        UnreadAccessor.Missing
    }

    private fun logUsernameFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!usernameFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun isUnread(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = unreadAccessorCache.computeIfAbsent(modelClass, ::findUnreadAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val unreadCount = ((accessor as UnreadAccessor.Field).get(conversation) as? Number)
                ?.toInt() ?: return false
            unreadCount > 0
        } catch (error: Exception) {
            logUnreadFailureOnce(modelClass, "could not read field_unReadCount", error)
            false
        }
    }

    private fun findUnreadAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_unReadCount"
            superclass()
        } ?: run {
            logUnreadFailureOnce(modelClass, "field_unReadCount is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUnreadFailureOnce(modelClass, "could not resolve field_unReadCount", error)
        UnreadAccessor.Missing
    }

    private fun logUnreadFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!unreadFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

    private fun updateDividerRequest() {
        WeConversationListViewApi.setDividerHidden(owner = this, hidden = isEnabled && hideDividersEnabled)
    }
    private fun isInsideChatting(view: View): Boolean {
        globalChattingCache[view]?.let { return it }
        var result = false
        var cur: View? = view
        var depth = 0
        while (cur != null && depth < 40) {
            val name = cur.javaClass.name
            if (name.contains("Chatting") || name.contains("chatting") ||
                name.contains("ChatUI") || name.contains("ChattingUI")
            ) {
                result = true
                break
            }
            cur = cur.parent as? View
            depth++
        }
        globalChattingCache[view] = result
        return result
    }

    /** 是否通用列表（RecyclerView / ListView），排除聊天页 */
    private fun isGenericList(view: View): Boolean {
        globalListCache[view]?.let { return it }
        val name = view.javaClass.name
        val result = !isInsideChatting(view) && (
            name.contains("RecyclerView") ||
                name.endsWith("ListView") ||
                name.contains("PullDownListView")
            )
        globalListCache[view] = result
        return result
    }

    /** 是否为分隔线：1px 全宽细线，落在卡片缝隙里 */
    private fun isSeparator(view: View): Boolean =
        view.height <= 3 && view.width > 0

    /** 递归扫描视图树，给所有通用列表应用卡片 */
    private fun styleGlobalLists(view: View?, depth: Int) {
        if (view == null || depth > 18 || !globalCardsEnabled) return
        try {
            if (isGenericList(view)) {
                applyGlobalCards(view as ViewGroup)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    styleGlobalLists(view.getChildAt(i), depth + 1)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 应用卡片到列表的所有可见行 */
    private fun applyGlobalCards(list: ViewGroup) {
        val density = list.resources.displayMetrics.density
        val inset = (globalCardsInset.coerceIn(0, 60) * density).roundToInt()
        val gap = (globalCardsGap.coerceIn(0, 60) * density).roundToInt()
        val corner = (globalCardsRadius.coerceIn(1, 48) * density).roundToInt().toFloat()
        val alpha = globalCardsAlpha.coerceIn(0, 255)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (isSeparator(child)) {
                child.background = null
                continue
            }
            styleGlobalRow(child, inset, gap, corner, alpha)
        }
    }

    /** 样式化一行；多行容器则递归处理内部行 */
    private fun styleGlobalRow(row: View, inset: Int, gap: Int, corner: Float, alpha: Int) {
        if (isGlobalRowContainer(row)) {
            row.background = null
            resetGlobalMargins(row)
            val group = row as ViewGroup
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j) ?: continue
                if (!child.isShown) continue
                if (child.height >= dp(child, 34) && child.width * 10 >= group.width * 6) {
                    styleGlobalRow(child, inset, gap, corner, alpha)
                } else {
                    stripGlobalRowFills(child, 0)
                }
            }
            return
        }
        try {
            val lp = row.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val mlp = lp
                if (mlp.leftMargin != inset || mlp.rightMargin != inset || mlp.bottomMargin != gap) {
                    mlp.leftMargin = inset
                    mlp.rightMargin = inset
                    mlp.bottomMargin = gap
                    row.layoutParams = mlp
                }
            }
            val current = row.background
            val desired = GlobalCardDrawable(globalCardColour(), corner, alpha)
            if (current !is GlobalCardDrawable || !current.matches(corner, alpha)) {
                row.background = desired
            }
            globalCardDrawables[row] = desired
            if (row.width > 0 && row.height > 0 && !globalFillsStripped.containsKey(row)) {
                globalFillsStripped[row] = true
                stripGlobalRowFills(row, 0)
            }
            applyGlobalCardHeight(row)
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** 全局卡片颜色：深色模式用深底，否则白色 */
    private fun globalCardColour(): Int =
        if (globalCardsDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /** 多行容器判断：多个等高子行或单行但容器更高 */
    private fun isGlobalRowContainer(view: View): Boolean {
        if (view !is ViewGroup) return false
        val g = view
        var rows = 0
        var tallest = 0
        var firstH = -1
        var even = true
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i) ?: continue
            if (!c.isShown) continue
            val ch = c.height
            if (ch < 100) continue
            if (ch > tallest) tallest = ch
            if (firstH < 0) firstH = ch
            else if (abs(ch - firstH) > Math.max(4, firstH / 8)) even = false
            rows++
        }
        return (rows >= 3 && even) || (rows == 1 && view.height > tallest + dp(view, 8))
    }

    /** 还原行 margin */
    private fun resetGlobalMargins(view: View) {
        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            val mlp = lp
            if (mlp.leftMargin != 0 || mlp.rightMargin != 0 || mlp.bottomMargin != 0) {
                mlp.leftMargin = 0
                mlp.rightMargin = 0
                mlp.bottomMargin = 0
                view.layoutParams = mlp
            }
        }
    }

    /** 清掉行内部的底板，让卡片真正露出来 */
    private fun stripGlobalRowFills(view: View, depth: Int) {
        if (view == null || depth > 4 || view !is ViewGroup) return
        try {
            val g = view
            val w = view.width
            val h = view.height
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i) ?: continue
                if (!c.isShown) continue
                val wide = c.width * 10 >= w * 6
                val plate = wide && h > 0 && w > 0 &&
                    (c.height * 10 >= h * 7 || c.height >= dp(c, 20))
                if (plate && isGlobalOpaqueFill(c)) c.background = null
                stripGlobalRowFills(c, depth + 1)
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 判断是否为不透明底板 */
    private fun isGlobalOpaqueFill(view: View): Boolean {
        try {
            val d = view.background ?: return false
            if (d is GlobalCardDrawable) return false
            if (d is ColorDrawable) return ((d.color ushr 24) and 0xFF) > 200
            if (d is GradientDrawable) {
                val csl = d.color ?: return false
                return ((csl.defaultColor ushr 24) and 0xFF) > 200
            }
            val n = d.javaClass.name
            return n.contains("StateListDrawable") || n.contains("NinePatchDrawable")
        } catch (ignored: Throwable) {
            return false
        }
    }

    /** 卡片高度增量：每行只记一次原始高度，重复不累积 */
    private fun applyGlobalCardHeight(row: View) {
        try {
            val dh = (globalCardsHeight.coerceIn(-24, 48) * row.resources.displayMetrics.density).roundToInt()
            if (dh == 0) return
            val lp = row.layoutParams ?: return
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height <= 0) return
            val orig = globalOrigHeights[row]
                ?: (if (row.height > 0) row.height else lp.height).also {
                    if (it <= 0) return
                    globalOrigHeights[row] = it
                }
            val want = Math.max(1, orig + dh)
            if (lp.height != want) {
                lp.height = want
                row.layoutParams = lp
            }
        } catch (ignored: Throwable) {
            // 单行失败不影响其它行
        }
    }

    /** dp 转 px */
    private fun dp(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).roundToInt()

    /** 关闭全局卡片：清理所有缓存，页面重建后微信自然恢复原始背景 */
    private fun restoreGlobalCards() {
        globalFillsStripped.clear()
        globalOrigHeights.clear()
        globalCardDrawables.clear()
        globalChattingCache.clear()
        globalListCache.clear()
        globalPendingLists.clear()
    }

    /**
     * 将滚动触发的样式刷新延迟到下一帧，并对同一列表去重。
     * onScrollChanged 发生在 ViewGroup 遍历/绘制过程中，不能同步修改子 View 的
     * layoutParams、margin 或 background，否则可能破坏 dispatchDraw 的子项快照。
     */
    private fun scheduleGlobalCards(list: ViewGroup) {
        if (globalPendingLists.put(list, true) == true) return
        list.post {
            globalPendingLists.remove(list)
            if (!globalCardsEnabled || !list.isAttachedToWindow) return@post
            try {
                applyGlobalCards(list)
            } catch (ignored: Throwable) {
                // 仅外观
            }
        }
    }

    /** 立即对当前可见 Activity 应用全局卡片（开关切换时调用） */
    private fun applyGlobalCardsNow() {
        if (!globalCardsEnabled) return
        try {
            val activity = getTopMostActivity(allowPaused = true) ?: return
            val decor = activity.window?.decorView ?: return
            decor.post {
                if (decor.width > 0 && decor.height > 0) {
                    styleGlobalLists(decor, 0)
                }
            }
        } catch (ignored: Throwable) {
            // 仅外观
        }
    }

    /** 全局 Activity.onResume hook：页面可见时应用卡片 */
    private fun registerGlobalHooks() {
        if (globalHooksRegistered) return
        globalHooksRegistered = true

        Activity::class.reflekt().apply {
            firstMethod {
                name = "onResume"
                parameterCount = 0
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val activity = thisObject as Activity
                val decor = activity.window?.decorView ?: return@hookAfter
                decor.postDelayed({
                    if (decor.width > 0 && decor.height > 0) {
                        styleGlobalLists(decor, 0)
                    }
                }, 60L)
            }
        }

        View::class.reflekt().apply {
            firstMethod {
                name = "onScrollChanged"
                parameterCount = 4
            }.hookAfter {
                if (!globalCardsEnabled) return@hookAfter
                val self = thisObject as? View ?: return@hookAfter
                if (self !is ViewGroup) return@hookAfter
                if (!isGenericList(self)) return@hookAfter
                scheduleGlobalCards(self)
            }
        }
    }
}
