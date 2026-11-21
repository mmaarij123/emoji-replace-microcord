package io.github.mmaarij123.emojireplace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class EmojiImageLoader(initialPack: EmojiPack, private val catalog: EmojiPackCatalog) {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val workers = ThreadPoolExecutor(4, 4, 20L, TimeUnit.SECONDS, ArrayBlockingQueue(384)) { task ->
        Thread(task, "Emoji Replace images").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    // Apart from the stop flag, these fields are accessed only on the main thread.
    private val cache = object : LruCache<EmojiArtwork, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: EmojiArtwork, value: Bitmap): Int = value.allocationByteCount
    }
    private val retryAfter = LruCache<EmojiArtwork, Long>(1024)
    private val pending = mutableMapOf<EmojiArtwork, Request>()
    private var selected = initialPack
    private var bundledLoads = 0
    private var lastFailure: String? = null

    fun diagnostics(): String = buildString {
        append("Pack images decoded offline: $bundledLoads")
        lastFailure?.let { append("\nLast image error: ").append(it) }
    }

    fun select(pack: EmojiPack) {
        if (selected == pack) return
        selected = pack
        pending.clear()
        workers.queue.clear()
    }

    fun load(pack: EmojiPack, key: EmojiArtwork, target: EmojiImageTarget): Boolean {
        if (closed.get() || pack != selected) return false
        cache.get(key)?.let { bitmap ->
            target.onImageLoaded(bitmap)
            return true
        }
        if ((retryAfter.get(key) ?: 0L) > SystemClock.elapsedRealtime()) return false

        val listener = WeakReference(target)
        pending[key]?.let { request ->
            request.listeners.add(listener)
            return true
        }
        val request = Request(mutableListOf(listener))
        pending[key] = request
        try {
            workers.execute {
                val result = fetch(key)
                main.post { finish(key, request, result) }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
            return false
        }
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdownNow()
        main.post {
            pending.clear()
            cache.evictAll()
            retryAfter.evictAll()
        }
    }

    private fun finish(key: EmojiArtwork, request: Request, result: ImageResult) {
        if (closed.get() || pending[key] !== request) return
        pending.remove(key)
        val bitmap = result.bitmap
        if (bitmap == null) {
            retryAfter.put(key, SystemClock.elapsedRealtime() + 30_000)
            lastFailure = result.error
        } else {
            cache.put(key, bitmap)
            bundledLoads++
        }
        request.listeners.forEach { it.get()?.onImageLoaded(bitmap) }
    }

    private fun fetch(key: EmojiArtwork): ImageResult = try {
        val bitmap = catalog.open(key).use { asset -> asset.createInputStream().use { BitmapFactory.decodeStream(it) } }
        ImageResult(bitmap, if (bitmap == null) "Image could not be decoded" else null)
    } catch (failure: IOException) {
        ImageResult(null, failure.message ?: failure.javaClass.simpleName)
    }

    private class Request(val listeners: MutableList<WeakReference<EmojiImageTarget>>)
    private class ImageResult(val bitmap: Bitmap?, val error: String? = null)
}

internal fun interface EmojiImageTarget {
    fun onImageLoaded(bitmap: Bitmap?)
}

internal class PackEmojiDrawable(private val fallback: Drawable, view: View) : Drawable(), EmojiImageTarget {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val owner = WeakReference(view)
    private var bitmap: Bitmap? = null

    init {
        bounds = fallback.bounds
    }

    override fun draw(canvas: Canvas) {
        val image = bitmap
        if (image == null) fallback.draw(canvas) else canvas.drawBitmap(image, null, bounds, paint)
    }

    override fun onImageLoaded(bitmap: Bitmap?) {
        this.bitmap = bitmap
        if (bitmap != null) owner.get()?.invalidate()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fallback.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fallback.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
