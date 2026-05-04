package org.flatgram.messenger.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

internal object MediaPreviewBinder {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2)
    private val pendingViewsByPath = ConcurrentHashMap<String, MutableList<PendingMediaView>>()
    private val pendingDecodesByPath = ConcurrentHashMap.newKeySet<String>()
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun bind(
        view: ImageView,
        placeholder: TextView,
        path: String?,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        if (path.isNullOrBlank()) {
            view.setImageDrawable(null)
            view.tag = null
            placeholder.visibility = View.VISIBLE
            placeholder.tag = null
            return false
        }

        val cachedBitmap = bitmapCache.get(path)
        if (cachedBitmap != null) {
            view.setImageBitmap(cachedBitmap)
            view.tag = path
            placeholder.visibility = View.GONE
            placeholder.tag = path
            return true
        }

        if (view.tag != path) {
            view.setImageDrawable(null)
        }
        view.tag = path
        placeholder.tag = path
        placeholder.visibility = View.VISIBLE
        val pendingViews = pendingViewsByPath.getOrPut(path) {
            Collections.synchronizedList(mutableListOf())
        }
        pendingViews.add(PendingMediaView(WeakReference(view), WeakReference(placeholder)))
        if (!pendingDecodesByPath.add(path)) return false

        decodeExecutor.execute {
            val bitmap = decodeSampledBitmap(
                path = path,
                reqWidth = max(targetWidth, DEFAULT_MEDIA_WIDTH),
                reqHeight = max(targetHeight, DEFAULT_MEDIA_HEIGHT)
            )
            completeDecode(path, bitmap)
        }
        return false
    }

    private fun completeDecode(path: String, bitmap: Bitmap?) {
        if (bitmap != null) {
            bitmapCache.put(path, bitmap)
        }
        val views = pendingViewsByPath.remove(path).orEmpty()
        pendingDecodesByPath.remove(path)
        mainHandler.post {
            val cachedBitmap = bitmap ?: bitmapCache.get(path) ?: return@post
            views.forEach { pending ->
                val pendingView = pending.image.get() ?: return@forEach
                if (pendingView.tag != path) return@forEach
                pendingView.setImageBitmap(cachedBitmap)
                pending.placeholder.get()?.let { placeholder ->
                    if (placeholder.tag == path) {
                        placeholder.visibility = View.GONE
                    }
                }
            }
        }
    }

    private data class PendingMediaView(
        val image: WeakReference<ImageView>,
        val placeholder: WeakReference<TextView>
    )

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun cacheSize(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxMemoryKb / 16
    }

    private const val DEFAULT_MEDIA_WIDTH = 512
    private const val DEFAULT_MEDIA_HEIGHT = 384
}
