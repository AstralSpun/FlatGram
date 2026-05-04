package org.flatgram.messenger.adapter

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import org.flatgram.messenger.sticker.StickerAnimScheduler
import org.flatgram.messenger.sticker.StickerController
import org.flatgram.messenger.sticker.StickerRegistry
import org.flatgram.messenger.td.AnimatedEmojiFormat
import org.flatgram.messenger.td.MessageContentUi
import java.io.File

class AnimatedEmojiPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isVisible = false
    }
    private val textureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        isVisible = false
    }

    private var boundKey: String? = null
    private var boundVideoPath: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var stickerController: StickerController? = null
    private var stickerPath: String? = null
    private var stickerWidth = 0
    private var stickerHeight = 0
    private var schedulerAttached = false
    private val frameListener: () -> Unit = {
        stickerController?.let { controller ->
            if (imageView.drawable == null) {
                imageView.setImageBitmap(controller.bitmap)
            }
            if (!imageView.isVisible) imageView.isVisible = true
            imageView.invalidate()
        }
    }

    init {
        addView(imageView)
        addView(textureView)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                val path = boundVideoPath ?: return
                startWebm(path, surfaceTexture)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releaseWebm()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun bind(content: MessageContentUi.AnimatedEmoji): Boolean {
        val path = content.stickerPath
        if (path.isNullOrBlank()) {
            clear()
            return false
        }

        val format = content.format.resolvedFromFile(path)
        val key = "$format:$path"
        if (boundKey == key) return true

        clear()
        boundKey = key
        return when (format) {
            AnimatedEmojiFormat.TGS -> bindTgs(path)
            AnimatedEmojiFormat.WEBP -> bindWebp(path)
            AnimatedEmojiFormat.WEBM -> bindWebm(path)
            AnimatedEmojiFormat.UNKNOWN -> false
        }
    }

    fun clear() {
        boundKey = null
        boundVideoPath = null
        // Detach UI references first so the buffer pool / registry won't be reused
        // under a still-displayed bitmap.
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
        imageView.setImageDrawable(null)
        imageView.isVisible = false
        textureView.isVisible = false
        releaseSticker()
        releaseWebm()
    }

    fun pause() {
        if (schedulerAttached) {
            stickerController?.let(StickerAnimScheduler::remove)
            schedulerAttached = false
        }
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
        mediaPlayer?.runCatching { pause() }
    }

    fun resume() {
        val controller = stickerController
        if (controller != null && !schedulerAttached) {
            StickerAnimScheduler.add(controller)
            schedulerAttached = true
        }
        (imageView.drawable as? AnimatedImageDrawable)?.start()
        mediaPlayer?.runCatching { start() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    private fun bindTgs(path: String): Boolean {
        val width = targetRenderWidth()
        val height = targetRenderHeight()
        val controller = runCatching {
            StickerRegistry.acquireTgs(path, width, height)
        }.getOrElse {
            boundKey = null
            return false
        }

        stickerPath = path
        stickerWidth = width
        stickerHeight = height
        stickerController = controller
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setImageBitmap(controller.bitmap)
        imageView.isVisible = controller.hasRenderedFirstFrame
        controller.addFrameListener(frameListener)
        if (isAttachedToWindow && !schedulerAttached) {
            StickerAnimScheduler.add(controller)
            schedulerAttached = true
        }
        return true
    }

    private fun bindWebp(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            boundKey = null
            return false
        }
        return bindAnimatedImageDrawable(path)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun bindAnimatedImageDrawable(path: String): Boolean {
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path)))
        }.getOrNull() ?: run {
            boundKey = null
            return false
        }

        imageView.setImageDrawable(drawable)
        imageView.isVisible = true
        (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        (drawable as? AnimatedImageDrawable)?.start()
        return true
    }

    private fun bindWebm(path: String): Boolean {
        boundVideoPath = path
        textureView.isVisible = true
        textureView.surfaceTexture?.let { startWebm(path, it) }
        return true
    }

    private fun startWebm(path: String, surfaceTexture: SurfaceTexture) {
        releaseWebm()
        val surface = Surface(surfaceTexture)
        videoSurface = surface
        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.setSurface(surface)
            player.setDataSource(context, Uri.fromFile(File(path)))
            player.isLooping = true
            player.setOnPreparedListener { it.start() }
            player.setOnErrorListener { _, _, _ ->
                boundKey = null
                releaseWebm()
                textureView.isVisible = false
                true
            }
            player.prepareAsync()
        }.onFailure {
            boundKey = null
            releaseWebm()
            textureView.isVisible = false
        }
    }

    private fun releaseWebm() {
        mediaPlayer?.runCatching {
            setOnPreparedListener(null)
            setOnErrorListener(null)
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        videoSurface?.release()
        videoSurface = null
    }

    private fun releaseSticker() {
        val controller = stickerController
        val path = stickerPath
        if (controller != null) {
            if (schedulerAttached) {
                StickerAnimScheduler.remove(controller)
                schedulerAttached = false
            }
            controller.removeFrameListener(frameListener)
        }
        if (path != null && stickerWidth > 0 && stickerHeight > 0) {
            StickerRegistry.release(path, stickerWidth, stickerHeight)
        }
        stickerController = null
        stickerPath = null
        stickerWidth = 0
        stickerHeight = 0
    }

    private fun targetRenderWidth(): Int {
        return width.takeIf { it > 0 }
            ?: layoutParams?.width?.takeIf { it > 0 }
            ?: DEFAULT_STICKER_SIZE_PX
    }

    private fun targetRenderHeight(): Int {
        return height.takeIf { it > 0 }
            ?: layoutParams?.height?.takeIf { it > 0 }
            ?: DEFAULT_STICKER_SIZE_PX
    }

    private fun AnimatedEmojiFormat.resolvedFromFile(path: String): AnimatedEmojiFormat {
        if (this != AnimatedEmojiFormat.UNKNOWN) return this
        File(path).readHeaderOrNull()?.let { header ->
            if (header.isGzip()) return AnimatedEmojiFormat.TGS
            if (header.isRiffWebp()) return AnimatedEmojiFormat.WEBP
            if (header.isWebm()) return AnimatedEmojiFormat.WEBM
        }
        return when {
            path.endsWith(".tgs", ignoreCase = true) -> AnimatedEmojiFormat.TGS
            path.endsWith(".webm", ignoreCase = true) -> AnimatedEmojiFormat.WEBM
            path.endsWith(".webp", ignoreCase = true) -> AnimatedEmojiFormat.WEBP
            else -> AnimatedEmojiFormat.UNKNOWN
        }
    }

    private fun File.readHeaderOrNull(): ByteArray? {
        return runCatching {
            inputStream().use { input ->
                ByteArray(FILE_HEADER_SIZE).also { buffer ->
                    val read = input.read(buffer)
                    if (read <= 0) return null
                }
            }
        }.getOrNull()
    }

    private fun ByteArray.isGzip(): Boolean {
        return size >= 2 && this[0].toInt() == GZIP_MAGIC_0 && this[1].toInt() == GZIP_MAGIC_1
    }

    private fun ByteArray.isRiffWebp(): Boolean {
        if (size < 12) return false
        return this[0] == 'R'.code.toByte() &&
            this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() &&
            this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() &&
            this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() &&
            this[11] == 'P'.code.toByte()
    }

    private fun ByteArray.isWebm(): Boolean {
        if (size < 4) return false
        return this[0] == 0x1a.toByte() &&
            this[1] == 0x45.toByte() &&
            this[2] == 0xdf.toByte() &&
            this[3] == 0xa3.toByte()
    }

    private companion object {
        const val FILE_HEADER_SIZE = 16
        const val GZIP_MAGIC_0 = 0x1f
        const val GZIP_MAGIC_1 = 0x8b
        const val DEFAULT_STICKER_SIZE_PX = 336
    }
}
