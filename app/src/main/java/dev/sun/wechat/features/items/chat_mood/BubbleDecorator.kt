package dev.sun.wechat.features.items.chat_mood

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import dev.sun.wechat.utils.WeLogger
import java.util.IdentityHashMap

/**
 * 在真实文字气泡下方追加一个同级小卡片（不替换宿主行/ViewHolder），
 * 显示情绪分析结果。移植自 Yanwai BubbleDecorator。
 */
object BubbleDecorator {
    private val TAG_D = "BubbleDecorator"
    private data class Card(
        val key: String, val view: TextView, val parent: ViewGroup, val row: View,
        val anchor: View, val assignedId: Int?, val input: AnalysisInput,
        val detach: View.OnAttachStateChangeListener,
    )
    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()
    private val refreshRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun show(row: View, message: AnalysisInput?): Boolean {
        if (message == null || !MoodAnalyzer.enabled || !MoodAnalyzer.showBadge) { clear(row); return false }
        // 首次绘制时挂上完成回调，之后结果落地就能直接改这张卡的文字。
        if (refreshRegistered.compareAndSet(false, true)) MoodAnalyzer.onRefresh(::refreshAll)
        val key = message.key
        var state = cards[row]
        if (state != null && (state.key != key || state.view.parent !== state.parent)) {
            clear(row)
            state = null
        }
        if (state == null) {
            state = attach(row, key, message) ?: return false
            cards[row] = state
        }
        val value = cardText(key)
        if (state.view.text.toString() != value) state.view.text = value
        return true
    }

    /** 卡片文案：结果 → 失败 → 未分析/进行中。 */
    private fun cardText(key: String): String {
        MoodStore.get(key)?.detail?.let { return it }
        MoodAnalyzer.failure(key)?.let { return "${MoodAnalyzer.header}\n分析失败：$it\n点击此卡重试" }
        if (!MoodAnalyzer.enabled) return "${MoodAnalyzer.header}\n模型未配置"
        // 只对真正提交过的消息显示「正在分析」，其余提示尚未分析，避免永久转圈。
        return if (MoodStore.isPending(key)) "${MoodAnalyzer.header}\n正在分析…"
        else "${MoodAnalyzer.header}\n点击此卡分析"
    }

    /**
     * 分析完成/失败后由 MoodAnalyzer 回调触发：就地刷新所有已挂载卡片。
     * 不重新 onMessageViewAttached，所以不会再提交一次分析。
     */
    private fun refreshAll() {
        cards.entries.toList().forEach { (row, state) ->
            if (!row.isAttachedToWindow) { clear(row); return@forEach }
            updateCard(state.key)
        }
    }

    /** 卡片当前文案与状态不符时才写回，避免无谓重绘。 */
    private fun updateCard(key: String) {
        val state = cards.values.firstOrNull { it.key == key } ?: return
        val text = cardText(key)
        if (state.view.text.toString() != text) state.view.text = text
    }

    private fun attach(row: View, key: String, input: AnalysisInput): Card? {
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        val rowPos = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorPos = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val left = (anchorPos[0] - rowPos[0]).coerceAtLeast(0)
        val width = minOf(dp(row, 300), root.width - left - dp(row, 16))
        if (width < dp(row, 100)) return null
        val dark = row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val card = TextView(row.context).apply {
            id = View.generateViewId()
            textSize = 12f
            setPadding(dp(row, 8), dp(row, 6), dp(row, 8), dp(row, 6))
            setTextColor(if (dark) 0xFFE2E2E7.toInt() else 0xFF34343A.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(row, 4).toFloat()
                setColor(if (dark) 0xFF26262B.toInt() else 0xFFDDDEE2.toInt())
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                // 失败或从未分析：点一下重新提交（键稳定，不会因为点击产生重复缓存条目）
                MoodAnalyzer.retryFailure(key)
                MoodAnalyzer.submit(input) { isAttachedToWindow }
                updateCard(key)
            }
        }
        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        var target: ViewGroup? = null
        var assignedId: Int? = null
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                val parentPos = IntArray(2).also { parent.getLocationOnScreen(it) }
                val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (anchorPos[0] - parentPos[0] - parent.paddingLeft).coerceAtLeast(0)
                    topMargin = dp(row, 3)
                    bottomMargin = dp(row, 6)
                }
                parent.addView(card, lp)
                target = parent
                break
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        if (target == null && root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val branchParams = branch.layoutParams as? RelativeLayout.LayoutParams ?: return null
            if (branchParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return null
            if (branch.id == View.NO_ID) {
                assignedId = View.generateViewId()
                branch.id = assignedId
            }
            val lp = RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                leftMargin = left
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            root.addView(card, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) WeLogger.w(TAG_D, "暂不绘制未知气泡布局：$signature")
            return null
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { clear(v) }
        }
        row.addOnAttachStateChangeListener(detach)
        return Card(key, card, target, row, branch, assignedId, input, detach)
    }

    private fun findBubble(root: ViewGroup): View? {
        val holder = root.tag
        if (holder != null) {
            val method = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
            val main = runCatching { method?.isAccessible = true; method?.invoke(holder) as? View }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun clear(row: View) {
        val state = cards.remove(row) ?: return
        row.removeOnAttachStateChangeListener(state.detach)
        (state.view.parent as? ViewGroup)?.removeView(state.view)
        if (state.assignedId != null && state.anchor.id == state.assignedId) state.anchor.id = View.NO_ID
    }
    fun clearAll() { cards.keys.toList().forEach(::clear); refreshRegistered.set(false) }
    fun prune() { cards.keys.filter { !it.isAttachedToWindow }.forEach(::clear) }
    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}