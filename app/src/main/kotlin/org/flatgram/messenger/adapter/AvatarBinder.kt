package org.flatgram.messenger.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

internal object AvatarBinder {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2)
    private val pendingViewsByPath = ConcurrentHashMap<String, MutableList<WeakReference<TextView>>>()
    private val bitmapCache = object : LruCache<String, Bitmap>(avatarCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun bind(
        view: TextView,
        title: String,
        avatarPath: String?,
        @DrawableRes placeholderBackground: Int
    ) {
        view.text = title.firstOrNull()?.uppercaseChar()?.toString().orEmpty()

        if (avatarPath.isNullOrBlank()) {
            if (view.background == null) {
                view.setBackgroundResource(placeholderBackground)
            }
            view.tag = null
            return
        }

        val cachedBitmap = bitmapCache.get(avatarPath)
        if (cachedBitmap != null) {
            view.text = null
            view.background = roundedDrawable(view, cachedBitmap)
            view.tag = avatarPath
            return
        }

        view.setBackgroundResource(placeholderBackground)
        view.tag = avatarPath
        val pendingViews = pendingViewsByPath.getOrPut(avatarPath) {
            Collections.synchronizedList(mutableListOf())
        }
        pendingViews.add(WeakReference(view))
        if (pendingViews.size > 1) return

        decodeExecutor.execute {
            val targetSize = max(view.width, view.height).takeIf { it > 0 } ?: DEFAULT_AVATAR_SIZE
            val bitmap = decodeSampledBitmap(avatarPath, targetSize, targetSize)
            if (bitmap != null) {
                bitmapCache.put(avatarPath, bitmap)
            }
            val views = pendingViewsByPath.remove(avatarPath).orEmpty()
            mainHandler.post {
                if (bitmap == null) return@post
                views.forEach { viewReference ->
                    val pendingView = viewReference.get() ?: return@forEach
                    if (pendingView.tag != avatarPath) return@forEach
                    pendingView.text = null
                    pendingView.background = roundedDrawable(pendingView, bitmap)
                }
            }
        }
    }

    private fun roundedDrawable(
        view: TextView,
        bitmap: Bitmap
    ): android.graphics.drawable.Drawable {
        return RoundedBitmapDrawableFactory.create(view.resources, bitmap).apply {
            isCircular = true
        }
    }

    private fun avatarCacheSize(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return maxMemoryKb / 32
    }

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

    private const val DEFAULT_AVATAR_SIZE = 96
}
