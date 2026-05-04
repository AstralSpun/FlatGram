package org.flatgram.messenger.sticker

import android.graphics.Bitmap
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope

class StickerController(
    json: String,
    targetWidth: Int,
    targetHeight: Int
) : Closeable {

    @Volatile private var nativePtr: Long = 0L
    @Volatile private var newFrameAvailable = false
    @Volatile private var hasPendingNativeFrame = false
    @Volatile private var pendingFrameStartMs: Long = 0L
    @Volatile var isReleased: Boolean = false
        private set
    @Volatile var hasRenderedFirstFrame: Boolean = false
        private set

    val stickerWidth: Int
    val stickerHeight: Int
    val bitmap: Bitmap

    private val renderWidth = targetWidth.coerceAtLeast(1)
    private val renderHeight = targetHeight.coerceAtLeast(1)
    private val totalFrames: Int
    private val frameDelayMs: Long
    private var nextFrameTimeMs = 0L
    private var startTimeMs = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val nativeCallback = object {
        @androidx.annotation.Keep
        fun onNativeFrameReady(rendered: Boolean) {
            if (isReleased) return
            hasPendingNativeFrame = false
            if (!rendered) return
            hasRenderedFirstFrame = true
            // Set flag and let scheduler dispatch on next vsync; this stays
            // off the JNI worker thread and avoids a mainHandler.post hop.
            newFrameAvailable = true
        }
    }

    init {
        if (!NativeStickerCore.isAvailable) {
            throw IllegalStateException("Native sticker renderer is unavailable")
        }
        nativePtr = NativeStickerCore.createLottieHandle(json)
        if (nativePtr == 0L) {
            throw IllegalArgumentException("Unable to create lottie sticker handle")
        }

        val size = IntArray(2)
        NativeStickerCore.getLottieSize(nativePtr, size)
        stickerWidth = size[0].coerceAtLeast(1)
        stickerHeight = size[1].coerceAtLeast(1)
        NativeStickerCore.prepareLottieRendering(nativePtr, renderWidth, renderHeight)

        totalFrames = NativeStickerCore.getLottieFrameCount(nativePtr).coerceAtLeast(1)
        val fps = NativeStickerCore.getLottieFrameRate(nativePtr).toInt().coerceIn(1, 60)
        frameDelayMs = (1000L / fps).coerceAtLeast(16L)

        // Software ARGB_8888 bitmap.  We deliberately avoid HardwareBuffer-backed
        // bitmaps for ImageView display: HWUI's texture cache keys off the
        // bitmap's generationId, which is NOT bumped when pixels are mutated
        // through AHardwareBuffer_lock/unlock — the result is that subsequent
        // invalidates redraw the cached texture instead of the new frame.
        // Software bitmaps bump generationId on every lockPixels/unlockPixels,
        // so HWUI re-uploads correctly and animation stays at full vsync rate.
        bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
    }

    fun tryDecodeNextFrame(currentTimeMs: Long, scope: CoroutineScope) {
        if (isReleased || currentTimeMs < nextFrameTimeMs) return
        if (hasPendingNativeFrame) {
            if (currentTimeMs - pendingFrameStartMs > PENDING_FRAME_TIMEOUT_MS) {
                hasPendingNativeFrame = false
            } else {
                return
            }
        }
        renderFrame(currentTimeMs)
        nextFrameTimeMs = currentTimeMs + frameDelayMs
    }

    private fun renderFrame(currentTimeMs: Long) {
        val ptr = nativePtr
        if (ptr == 0L) return
        if (startTimeMs == 0L) startTimeMs = currentTimeMs

        val frame = ((currentTimeMs - startTimeMs) / frameDelayMs % totalFrames).toInt()
        hasPendingNativeFrame = true
        pendingFrameStartMs = currentTimeMs
        if (!NativeStickerCore.renderLottieFrameAsync(ptr, bitmap, frame, nativeCallback)) {
            hasPendingNativeFrame = false
        }
    }

    fun checkNewFrameAvailable(): Boolean {
        if (!newFrameAvailable) return false
        newFrameAvailable = false
        return true
    }

    fun addFrameListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeFrameListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun dispatchFrameReady() {
        listeners.forEach { it.invoke() }
    }

    override fun close() {
        if (isReleased) return
        isReleased = true
        listeners.clear()
        StickerAnimScheduler.remove(this)
        val ptr = nativePtr
        if (ptr != 0L) {
            nativePtr = 0L
            NativeStickerCore.destroyLottieHandle(ptr)
        }
    }

    private companion object {
        const val PENDING_FRAME_TIMEOUT_MS = 1000L
    }
}
