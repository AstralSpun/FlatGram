package org.flatgram.messenger.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.util.concurrent.Executors

internal object AvatarBinder {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2)
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
            view.setBackgroundResource(placeholderBackground)
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
        decodeExecutor.execute {
            val bitmap = BitmapFactory.decodeFile(avatarPath) ?: return@execute
            bitmapCache.put(avatarPath, bitmap)
            mainHandler.post {
                if (view.tag != avatarPath) return@post
                view.text = null
                view.background = roundedDrawable(view, bitmap)
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
}
