package dev.kifranei.ampp.monet

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Re-tints views that were inflated before the cover colour changed (resource hooks only affect future reads).
 * Runs on the main thread but only after a settle delay and in small slices from the message queue, so it never
 * competes with the player-enter animation; every touched colour must be a remembered native/applied colour.
 */
internal class LiveRecolor(
    private val application: Application,
    private val mode: MonetMode,
    private val state: MonetState,
    private val book: ColorRoleBook,
    private val playerRootId: Int,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = WeakReference<Activity>(null)
    private var animator: ValueAnimator? = null
    private var generation = 0
    private var listener: AutoCloseable? = null
    private var closed = false
    private var walkedSeed: Int? = null
    // Main-thread work: any failure here would crash Apple Music, so it is contained and live recolour stops.
    private val guard = FailOpen("live recolour") { close() }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        listener = state.addListener { main.post { guard.run(Unit) { schedule() } } }
    }

    private fun schedule() {
        if (closed) return
        val mine = ++generation
        main.postDelayed({ guard.run(Unit) { if (mine == generation) walkWhenIdle(mine) } }, SETTLE_MS)
    }

    private fun walkWhenIdle(mine: Int) {
        Looper.myQueue().addIdleHandler {
            guard.run(Unit) { if (!closed && mine == generation) resumed.get()?.let { recolor(it) } }
            false
        }
    }

    private class Target(val view: View, val from: Int, val to: Int, val set: (Int) -> Unit)

    private fun recolor(activity: Activity) {
        val root = activity.window?.peekDecorView() ?: return
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val scheme = state.scheme(night) ?: return
        walkedSeed = state.currentSeed()
        val targets = ArrayList<Target>()
        val playerRoot: View? = if (playerRootId != 0) root.findViewById(playerRootId) else null
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_VIEWS && targets.size < MAX_TARGETS) {
            val view = stack.removeLast()
            if (view.visibility == View.GONE) continue
            val inPlayer = playerRoot != null && view.isDescendantOf(playerRoot)
            collect(view, scheme, includeAccent = mode.affectsApp, includePlayer = inPlayer, into = targets)
            if (view is ViewGroup) for (i in 0 until view.childCount) stack.addLast(view.getChildAt(i))
        }
        if (targets.isEmpty()) return
        animator?.cancel()
        val toApply = targets.toList()
        // Remember the final colours first so a following cover change can find them again.
        toApply.forEach { target -> book.roleOf(target.from)?.let { book.rememberApplied(target.to, it) } }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                guard.run(Unit) {
                    val t = a.animatedValue as Float
                    toApply.forEach { if (it.view.isAttachedToWindow) it.set(MonetCoverPalette.lerp(it.from, it.to, t)) }
                }
            }
            start()
        }
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var parent: Any? = this
        var depth = 0
        while (parent is View && depth++ < 64) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun collect(view: View, scheme: MonetScheme, includeAccent: Boolean, includePlayer: Boolean, into: MutableList<Target>) {
        fun add(current: Int?, set: (Int) -> Unit) {
            val from = current ?: return
            val to = book.replacement(from, scheme, includePlayer, includeAccent) ?: return
            into += Target(view, from, to, set)
        }
        fun tint(list: ColorStateList?, set: (ColorStateList) -> Unit) {
            if (list == null || list.isStateful) return
            add(list.defaultColor) { set(ColorStateList.valueOf(it)) }
        }
        if (view is TextView && !view.textColors.isStateful) add(view.currentTextColor) { view.setTextColor(it) }
        if (view is ImageView) tint(view.imageTintList) { view.imageTintList = it }
        tint(view.backgroundTintList) { view.backgroundTintList = it }
        if (view is ProgressBar) {
            tint(view.progressTintList) { view.progressTintList = it }
        }
        if (view is SeekBar) tint(view.thumbTintList) { view.thumbTintList = it }
        (view.background as? ColorDrawable)?.let { bg ->
            add(bg.color) { (view.background?.mutate() as? ColorDrawable)?.color = it }
        }
    }

    override fun onActivityResumed(activity: Activity) = guard.run(Unit) {
        resumed = WeakReference(activity)
        if (state.currentSeed() != null && state.currentSeed() != walkedSeed) schedule()
    }
    override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed = WeakReference(null) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun close() {
        if (closed) return
        closed = true
        generation++
        animator?.cancel()
        listener?.close()
        application.unregisterActivityLifecycleCallbacks(this)
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        const val SETTLE_MS = 900L
        const val DURATION_MS = 380L
        const val MAX_VIEWS = 4000
        const val MAX_TARGETS = 256
    }
}
