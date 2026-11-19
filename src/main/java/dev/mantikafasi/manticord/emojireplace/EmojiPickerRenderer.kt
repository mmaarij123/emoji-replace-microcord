package dev.mantikafasi.manticord.emojireplace

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.widgets.chat.input.emoji.WidgetEmojiAdapter
import java.util.IdentityHashMap

/** Observes Microcord's picker fragments, including those inside a bottom-sheet dialog. */
internal class EmojiPickerRenderer(
    private val application: Application,
    private val catalog: EmojiPackCatalog,
    private val images: EmojiImageLoader,
    initialPack: EmojiPack,
    private val unicodeNames: Map<String, ModelEmojiUnicode> = emptyMap(),
) : Application.ActivityLifecycleCallbacks {
    private val windows = IdentityHashMap<FragmentActivity, ActivityWatch>()
    private val pickers = IdentityHashMap<RecyclerView, ActivePicker>()
    private var pack = initialPack
    private var discovered = 0
    private var visible = 0
    private var displayed = 0
    private var frames = 0
    private var cells = 0

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(manager: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
            fragment.activity?.let { scan(it, view) }
        }

        override fun onFragmentResumed(manager: FragmentManager, fragment: Fragment) {
            val activity = fragment.activity ?: return
            fragment.view?.let { scan(activity, it) }
        }
    }

    fun start(currentContext: Context) {
        application.registerActivityLifecycleCallbacks(this)
        (currentContext as? Activity)?.let(::watchActivity)
    }

    fun select(newPack: EmojiPack) {
        if (pack == newPack) return
        pack = newPack
        visible = 0
        displayed = 0
        pickers.values.forEach { it.grid.packChanged() }
    }

    fun diagnostics(): String = "Picker grids found: $discovered\n" +
        "Picker passes: $frames · Image cells: $cells\n" +
        "Visible standard emojis: $visible\nReplacements shown: $displayed"

    fun refresh() {
        pickers.values.forEach { it.grid.refresh() }
    }

    fun close() {
        application.unregisterActivityLifecycleCallbacks(this)
        windows.forEach { (activity, watch) -> stopWatching(activity, watch) }
        windows.clear()
        pickers.values.forEach { it.grid.close() }
        pickers.clear()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = watchActivity(activity)
    override fun onActivityResumed(activity: Activity) = watchActivity(activity)

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is FragmentActivity) windows.remove(activity)?.let { stopWatching(activity, it) }
        val iterator = pickers.values.iterator()
        while (iterator.hasNext()) {
            val picker = iterator.next()
            if (picker.activity !== activity) continue
            picker.grid.close()
            iterator.remove()
        }
    }

    override fun onActivityStarted(activity: Activity) = watchActivity(activity)
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private fun watchActivity(activity: Activity) {
        if (activity !is FragmentActivity || windows.containsKey(activity)) return
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { scan(activity, root) }
        windows[activity] = ActivityWatch(root, listener)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        scan(activity, root)
        visitExisting(activity, activity.supportFragmentManager)
    }

    private fun stopWatching(activity: FragmentActivity, watch: ActivityWatch) {
        activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks)
        watch.root.viewTreeObserver.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(watch.listener)
    }

    private fun visitExisting(activity: Activity, manager: FragmentManager) {
        for (fragment in manager.fragments) {
            if (!fragment.isAdded) continue
            fragment.view?.let { scan(activity, it) }
            visitExisting(activity, fragment.childFragmentManager)
        }
    }

    private fun scan(activity: Activity, view: View) {
        if (view is RecyclerView) {
            if (view.adapter !is WidgetEmojiAdapter || pickers.containsKey(view)) return
            discovered++
            val grid = PickerGrid(view, catalog, images, unicodeNames, { pack }, { seen, standard, replaced ->
                frames++
                cells = maxOf(cells, seen)
                visible = maxOf(visible, standard)
                displayed = maxOf(displayed, replaced)
            }) { pickers.remove(view)?.grid?.close() }
            pickers[view] = ActivePicker(activity, grid)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) scan(activity, view.getChildAt(index))
        }
    }

    private class ActivePicker(val activity: Activity, val grid: PickerGrid)
    private class ActivityWatch(val root: View, val listener: ViewTreeObserver.OnGlobalLayoutListener)
}

private class PickerGrid(
    private val grid: RecyclerView,
    private val catalog: EmojiPackCatalog,
    private val images: EmojiImageLoader,
    private val unicodeNames: Map<String, ModelEmojiUnicode>,
    private val selectedPack: () -> EmojiPack,
    private val onFrame: (cells: Int, standard: Int, replaced: Int) -> Unit,
    private val onDetached: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, RecyclerView.OnChildAttachStateChangeListener {
    private val cells = IdentityHashMap<ImageView, PickerCell>()
    private var observer: ViewTreeObserver? = null
    private val update = Runnable { renderVisible() }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }

    init {
        grid.addOnAttachStateChangeListener(this)
        grid.addOnChildAttachStateChangeListener(this)
        grid.addOnLayoutChangeListener(layout)
        if (grid.isAttachedToWindow) onViewAttachedToWindow(grid)
    }

    override fun onViewAttachedToWindow(view: View) {
        observer = grid.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        refresh()
    }

    override fun onViewDetachedFromWindow(view: View) {
        onDetached()
    }

    private fun unobserve() {
        observer?.takeIf(ViewTreeObserver::isAlive)?.removeOnPreDrawListener(this)
        observer = null
        restoreCells()
    }

    override fun onChildViewAttachedToWindow(view: View) = refresh()

    override fun onChildViewDetachedFromWindow(view: View) {
        if (view is ImageView) cells.remove(view)?.restore()
    }

    fun packChanged() {
        restoreCells()
        refresh()
    }

    fun refresh() {
        grid.removeCallbacks(update)
        grid.post(update)
        grid.invalidate()
    }

    fun close() {
        grid.removeOnAttachStateChangeListener(this)
        grid.removeOnChildAttachStateChangeListener(this)
        grid.removeOnLayoutChangeListener(layout)
        grid.removeCallbacks(update)
        unobserve()
    }

    override fun onPreDraw(): Boolean {
        renderVisible()
        return true
    }

    private fun renderVisible() {
        val pack = selectedPack()
        if (pack == EmojiPack.DEFAULT || !grid.isAttachedToWindow) return
        val adapter = grid.adapter as? WidgetEmojiAdapter ?: return
        var seen = 0
        var standard = 0
        var replaced = 0
        for (index in 0 until grid.childCount) {
            val child = grid.getChildAt(index)
            val cell = child as? ImageView ?: continue
            seen++
            val adapterPosition = grid.getChildAdapterPosition(child)
            val item = adapter.getItem(adapterPosition)
            val emoji = if (item != null) {
                ((item as? WidgetEmojiAdapter.Item.EmojiItem)?.emoji as? ModelEmojiUnicode)
            } else {
                val laidOut = adapter.getItem(grid.getChildLayoutPosition(child))
                if (laidOut is WidgetEmojiAdapter.Item.EmojiItem) laidOut.emoji as? ModelEmojiUnicode
                else if (laidOut == null) unicodeNames[cell.contentDescription?.toString()] else null
            }
            if (emoji != null) standard++
            val existing = cells[cell]
            if (existing != null && existing.emoji == emoji?.surrogates) {
                if (existing.render()) replaced++
                continue
            }
            cells.remove(cell)?.restore()
            if (emoji == null) continue
            val artwork = catalog.artwork(pack, emoji.firstName, emoji.names) ?: continue
            val replacement = PickerCell(cell, emoji.surrogates) { target ->
                images.load(pack, artwork, target)
            }
            cells[cell] = replacement
            if (replacement.render()) replaced++
        }
        onFrame(seen, standard, replaced)
    }

    private fun restoreCells() {
        cells.values.forEach(PickerCell::restore)
        cells.clear()
    }
}

/** Keeps the native ImageView, including its click handlers, ripple and recycled image binding. */
internal class PickerCell(
    private val view: ImageView,
    val emoji: String,
    private val request: (EmojiImageTarget) -> Boolean,
) : EmojiImageTarget {
    private var artwork: BitmapDrawable? = null
    private var stock: Drawable? = null
    private var loading = false
    private var retryAt = 0L
    private var active = true

    fun render(): Boolean {
        if (artwork == null) {
            val now = SystemClock.uptimeMillis()
            if (active && !loading && now >= retryAt) {
                loading = true
                if (!request(this)) {
                    loading = false
                    retryAt = now + 1000
                }
            }
        }
        // A cache hit may have filled the drawable synchronously.
        val drawable = artwork ?: return false
        if (view.drawable !== drawable) {
            stock = view.drawable
            view.setImageDrawable(drawable)
        }
        return true
    }

    override fun onImageLoaded(bitmap: Bitmap?) {
        if (!active) return
        loading = false
        if (bitmap == null) {
            retryAt = SystemClock.uptimeMillis() + 30_000
            return
        }
        artwork = BitmapDrawable(view.resources, bitmap).apply { isFilterBitmap = true }
        // Validate the current adapter binding before installing the image on the next draw.
        view.invalidate()
    }

    fun restore() {
        active = false
        if (artwork != null && view.drawable === artwork) view.setImageDrawable(stock)
        stock = null
        artwork = null
    }
}
