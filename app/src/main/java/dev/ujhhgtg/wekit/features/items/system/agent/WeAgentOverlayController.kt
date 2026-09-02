package dev.ujhhgtg.wekit.features.items.system.agent

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.OverlayMode
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.agent.WeAgentBall
import dev.ujhhgtg.wekit.ui.agent.WeAgentPanel
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Manages the WeAgent system overlay (`TYPE_APPLICATION_OVERLAY`): a draggable floating ball and an
 * expandable panel window, both added to the [WindowManager] rather than a host Activity's view
 * tree. This survives across all WeChat Activities (and even when WeChat is backgrounded) with no
 * per-Activity hooks.
 *
 * The overlay lives in WeChat's process, so the effective `SYSTEM_ALERT_WINDOW` grant is WeChat's;
 * we gate mounting on [Settings.canDrawOverlays] and toast guidance if it's missing.
 */
object WeAgentOverlayController {

    private const val TAG = "WeAgentOverlayController"

    private const val PREF_BALL_X = "weagent_ball_x"
    private const val PREF_BALL_Y = "weagent_ball_y"

    /** 悬浮球默认贴边隐藏开关（对应 Miss-WeChat 的 wc_music_player_start_hidden）。 */
    const val PREF_BALL_DOCK_TO_EDGE_KEY = "weagent_ball_dock_to_edge"

    /** 贴边隐藏时悬浮球露出的窄边宽度（dp），其余部分移出屏幕。 */
    private const val EDGE_VISIBLE_DP = 10

    /** 贴边吸附滑入动画步数与每步间隔（ms）。 */
    private const val DOCK_ANIM_STEPS = 8
    private const val DOCK_ANIM_STEP_MS = 16L

    private val wm: WindowManager
        get() = HostInfo.application.getSystemService<WindowManager>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null

    // Ball window position captured at drag start (absolute-offset dragging, set in onDragStart).
    private var dragStartX = 0
    private var dragStartY = 0

    /** Whether the ball window is currently attached to the [WindowManager]. */
    @Volatile
    var isShown = false
        private set

    /** Whether the feature is enabled (user wants the overlay). Distinct from actual attachment. */
    @Volatile
    private var desiredVisible = false

    /** Which visibility rule the ball follows (§ 界面 setting). */
    @Volatile
    private var mode = OverlayMode.DISABLED

    /** 悬浮球默认贴边隐藏（每次打开微信默认收起到屏幕右边缘，拖动松手吸附最近边缘）。 */
    @Volatile
    private var dockToEdge = false

    /**
     * 切换悬浮球贴边隐藏。开启时立即将当前球收起到最近边缘（滑入动画）；关闭时展开回全可见位置。
     * 必须在主线程调用。
     */
    fun setDockToEdge(enabled: Boolean) {
        if (dockToEdge == enabled) return
        dockToEdge = enabled
        WePrefs.putBool(PREF_BALL_DOCK_TO_EDGE_KEY, enabled)
        val v = ballView ?: return
        val p = ballParams ?: return
        if (enabled) {
            val target = dockedTargetX(v, p, nearestEdgeOf(v, p))
            animateXTo(v, p, target)
            WePrefs.putInt(PREF_BALL_X, target)
        } else {
            val target = expandedTargetX(v, p)
            animateXTo(v, p, target)
            // 关闭贴边时持久化展开位置，避免下次 attach 从负 x 恢复导致球不可见
            WePrefs.putInt(PREF_BALL_X, target)
        }
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(HostInfo.application)

    /**
     * Marks the overlay as desired (feature enabled) and reconciles visibility. Under
     * [OverlayMode.FOREGROUND_ONLY] the ball is only attached while WeChat is foreground; the
     * tracker drives later attach/detach. Idempotent.
     */
    fun show() {
        desiredVisible = true
        if (mode == OverlayMode.DISABLED) return
        if (!canDrawOverlays()) {
            showOverlayPermissionToast()
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        wireForegroundTracker()
        reconcile()
    }

    /** Marks the overlay as no longer desired and detaches it. */
    fun hide() {
        desiredVisible = false
        reconcile()
    }

    /**
     * Sets the ball's visibility rule. Registers the foreground tracker for
     * [OverlayMode.FOREGROUND_ONLY] so background transitions detach the ball, and reconciles
     * immediately (e.g. re-attaches if WeChat is already foreground, or detaches now if it isn't).
     */
    fun setMode(newMode: OverlayMode) {
        mode = newMode
        if (newMode == OverlayMode.FOREGROUND_ONLY) wireForegroundTracker()
        reconcile()
    }

    private fun wireForegroundTracker() {
        WeChatForegroundTracker.onChanged = { reconcile() }
        WeChatForegroundTracker.ensureRegistered()
    }

    /** True when the ball should currently be attached given desire, mode, permission, and foreground. */
    private fun shouldBeVisible(): Boolean = when (mode) {
        OverlayMode.DISABLED -> false
        OverlayMode.ALWAYS -> desiredVisible && canDrawOverlays()
        OverlayMode.FOREGROUND_ONLY ->
            desiredVisible && canDrawOverlays() && WeChatForegroundTracker.isForeground
    }

    /** Attaches or detaches the ball window to match [shouldBeVisible]. Must run on the main thread. */
    private fun reconcile() {
        val want = shouldBeVisible()
        if (want && !isShown) {
            runCatching { addBall() }.onFailure { WeLogger.e(TAG, "failed to add ball", it) }
            isShown = true
        } else if (!want && isShown) {
            removePanel()
            ballView?.let { runCatching { wm.removeView(it) } }
            ballView = null
            ballParams = null
            isShown = false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Ball window
    // -----------------------------------------------------------------------------------------

    private fun addBall() {
        dockToEdge = WePrefs.getBoolOrDef(PREF_BALL_DOCK_TO_EDGE_KEY, false)
        val params = baseLayoutParams(focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = WePrefs.getIntOrDef(PREF_BALL_X, 24)
            y = WePrefs.getIntOrDef(PREF_BALL_Y, 240)
            // 允许窗口移出屏幕边界：贴边隐藏时球大部移出屏幕，只留窄边。
            // 若无此 flag，FLAG_LAYOUT_IN_SCREEN 会把负 x 裁剪回 0，导致"靠边不隐藏"。
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        ballParams = params
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentBall(
                        state = WeAgentService.ballState.value,
                        onClick = {
                            // 贴边收起状态（大部在屏幕外）下点击先展开球；完全可见时才开面板
                            val v0 = ballView
                            val p0 = ballParams
                            if (dockToEdge && v0 != null && p0 != null && isDockedHidden(v0, p0)) {
                                animateXTo(v0, p0, expandedTargetX(v0, p0))
                                WePrefs.putInt(PREF_BALL_X, p0.x)
                            } else {
                                togglePanel()
                            }
                        },
                        onDragStart = {
                            ballParams?.let { dragStartX = it.x; dragStartY = it.y }
                        },
                        onDrag = { dx, dy ->
                            val p = ballParams
                            val v = ballView
                            if (p != null && v != null) {
                                p.x = dragStartX + dx.toInt()
                                p.y = dragStartY + dy.toInt()
                                runCatching { wm.updateViewLayout(v, p) }
                            }
                        },
                        onDragEnd = {
                            val p = ballParams ?: return@WeAgentBall
                            val v = ballView
                            if (v != null) {
                                if (dockToEdge) {
                                    val edge = nearestEdgeOf(v, p)
                                    val target = dockedTargetX(v, p, edge)
                                    // y 保持拖动结束位置；x 滑入到贴边收起位
                                    animateXTo(v, p, target)
                                    WePrefs.putInt(PREF_BALL_X, target)
                                } else {
                                    clampToScreen(v, p)
                                    runCatching { wm.updateViewLayout(v, p) }
                                    WePrefs.putInt(PREF_BALL_X, p.x)
                                }
                                WePrefs.putInt(PREF_BALL_Y, p.y)
                            }
                        },
                    )
                }
            }
        }
        ballView = view
        wm.addView(view, params)
        // 每次打开微信（attach）时，若开启贴边则默认收起到右边缘（只露窄边）
        if (dockToEdge) {
            val target = dockedTargetX(view, params, nearestEdgeOf(view, params))
            animateXTo(view, params, target)
        } else if (params.x < 0 || isDockedHidden(view, params)) {
            // 关闭贴边但残留收起位置：展开回完全可见
            val target = expandedTargetX(view, params)
            params.x = target
            runCatching { wm.updateViewLayout(view, params) }
            WePrefs.putInt(PREF_BALL_X, target)
        }
    }

    /** Keeps the ball fully on-screen after a drag. */
    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        val w = if (view.width > 0) view.width else (52 * metrics.density).toInt()
        val h = if (view.height > 0) view.height else (52 * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
    }

    /** 悬浮球宽度（未布局时回退 52dp）。 */
    private fun ballWidth(view: View): Int {
        val metrics = view.resources.displayMetrics
        return if (view.width > 0) view.width else (52 * metrics.density).toInt()
    }

    /** 球是否处于贴边收起状态（露出宽度 < 球宽的一半）。 */
    private fun isDockedHidden(view: View, params: WindowManager.LayoutParams): Boolean {
        val metrics = view.resources.displayMetrics
        val w = ballWidth(view)
        val visible = when {
            params.x < 0 -> params.x + w               // 左缘收起：露出部分 = x + w
            params.x + w > metrics.widthPixels -> metrics.widthPixels - params.x // 右缘收起
            else -> w                                   // 完全在屏幕内
        }
        return visible < w / 2
    }

    private const val EDGE_LEFT = 0
    private const val EDGE_RIGHT = 1

    /** 判断悬浮球当前更靠近哪一边。 */
    private fun nearestEdgeOf(view: View, params: WindowManager.LayoutParams): Int {
        val metrics = view.resources.displayMetrics
        val w = ballWidth(view)
        val centerX = params.x + w / 2f
        return if (centerX < metrics.widthPixels / 2f) EDGE_LEFT else EDGE_RIGHT
    }

    /**
     * 贴边收起后的目标 x：球大部移出屏幕，只露 [EDGE_VISIBLE_DP] 窄边。
     * 左缘：x = 窄边宽 - 球宽（负值）；右缘：x = 屏宽 - 窄边宽。
     */
    private fun dockedTargetX(view: View, params: WindowManager.LayoutParams, edge: Int): Int {
        val metrics = view.resources.displayMetrics
        val w = ballWidth(view)
        val visible = (EDGE_VISIBLE_DP * metrics.density).toInt()
        return if (edge == EDGE_LEFT) {
            visible - w
        } else {
            metrics.widthPixels - visible
        }
    }

    /** 展开后的目标 x：完全可见（与屏幕边缘留出与窄边等宽的呼吸边距）。 */
    private fun expandedTargetX(view: View, params: WindowManager.LayoutParams): Int {
        val metrics = view.resources.displayMetrics
        val w = ballWidth(view)
        val margin = (EDGE_VISIBLE_DP * metrics.density).toInt()
        return if (nearestEdgeOf(view, params) == EDGE_LEFT) {
            margin
        } else {
            (metrics.widthPixels - w - margin).coerceAtLeast(0)
        }
    }

    /**
     * X 轴插值滑入/滑出动画：把窗口从当前 x 平滑移动到 [targetX]。
     * 每步 updateViewLayout；动画期间的新调用会取消上一次（removeCallbacksAndMessages）。
     */
    private fun animateXTo(
        view: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        steps: Int = DOCK_ANIM_STEPS,
    ) {
        val from = params.x
        if (from == targetX) return
        mainHandler.removeCallbacksAndMessages(null)
        for (i in 1..steps) {
            val frac = i.toFloat() / steps
            val stepX = (from + (targetX - from) * frac).toInt()
            mainHandler.postDelayed({
                if (view.parent != null && ballParams === params) {
                    params.x = stepX
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }, i * DOCK_ANIM_STEP_MS)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Panel window
    // -----------------------------------------------------------------------------------------

    fun togglePanel() {
        if (panelView != null) removePanel() else addPanel()
    }

    /**
     * Opens the panel independently of the ball — used by entry points that don't go through the
     * overlay ball (e.g. the chat toolbar item), so the panel stays reachable with
     * [OverlayMode.DISABLED]. No-op when the panel is already up. Must run on the main thread.
     */
    fun openPanel() {
        if (panelView != null) return
        if (!canDrawOverlays()) {
            showOverlayPermissionToast()
            WeLogger.w(TAG, "no SYSTEM_ALERT_WINDOW permission for host process")
            return
        }
        addPanel()
    }

    private fun addPanel() {
        val params = baseLayoutParams(focusable = true).apply {
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        val owner = LifecycleOwnerProvider.lifecycleOwner
        val view = WeAgentPanelHost(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setBackHandler { removePanel() }
        }
        val composeView = ComposeView(HostInfo.application).apply {
            setLifecycleOwner(owner)
            setContent {
                InjectedUiTheme {
                    WeAgentPanel(
                        onDismiss = { removePanel() },
                        onBackHandlerChanged = view::setBackHandler,
                    )
                }
            }
        }
        view.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panelView = view
        runCatching { wm.addView(view, params) }.onFailure { WeLogger.e(TAG, "failed to add panel", it) }
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun showOverlayPermissionToast() {
        val localized = LocalizedContextFactory.create(
            HostInfo.application,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        )
        showToast(localized.getString(R.string.agent_overlay_permission_required))
    }

    // -----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun baseLayoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 可聚焦窗口（WeAgent 面板）在输入法弹出时自动缩小到键盘上方，
            // 避免底部发送栏被键盘遮住。
            if (focusable) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }
    }
}

/**
 * Root view for the focusable panel window. Unlike an Activity decor view, a ComposeView attached
 * directly through WindowManager has no Activity back dispatcher, so the window root handles both
 * legacy key dispatch and Android 13+ system Back itself.
 */
private class WeAgentPanelHost(context: Context) : FrameLayout(context) {
    private var backHandler: (() -> Unit)? = null
    private var systemBackCallback: Any? = null

    fun setBackHandler(handler: (() -> Unit)?) {
        backHandler = handler
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (backHandler != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val state = keyDispatcherState ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                state.startTracking(event, this)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && state.isTracking(event) && !event.isCanceled) {
                backHandler?.invoke()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            systemBackCallback = WeAgentPanelBackApi33.register(this) {
                backHandler?.invoke()
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) {
            WeAgentPanelBackApi33.unregister(this, systemBackCallback)
        }
        systemBackCallback = null
        super.onDetachedFromWindow()
    }
}

@RequiresApi(33)
private object WeAgentPanelBackApi33 {
    fun register(view: View, onBack: () -> Unit): Any? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback(onBack)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        return callback
    }

    fun unregister(view: View, callback: Any?) {
        if (callback is OnBackInvokedCallback) {
            view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        }
    }
}
