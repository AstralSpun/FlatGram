package org.flatgram.messenger.sticker

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object StickerAnimScheduler {
    private val activeStickers = ConcurrentHashMap.newKeySet<StickerController>()
    private val refCounts = ConcurrentHashMap<StickerController, AtomicInteger>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var choreographer: Choreographer? = null

    @Volatile
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (activeStickers.isEmpty()) {
                isRunning = false
                return
            }

            val now = System.currentTimeMillis()
            val iterator = activeStickers.iterator()
            while (iterator.hasNext()) {
                val sticker = iterator.next()
                if (sticker.isReleased) {
                    iterator.remove()
                    refCounts.remove(sticker)
                    continue
                }
                sticker.tryDecodeNextFrame(now, decoderScope)
                if (sticker.checkNewFrameAvailable()) {
                    sticker.dispatchFrameReady()
                }
            }

            choreographer?.postFrameCallback(this)
        }
    }

    fun add(sticker: StickerController) {
        if (sticker.isReleased) return
        val count = refCounts.computeIfAbsent(sticker) { AtomicInteger(0) }
        count.incrementAndGet()
        if (activeStickers.add(sticker)) {
            scheduleStart()
        }
    }

    fun remove(sticker: StickerController) {
        val count = refCounts[sticker] ?: return
        if (count.decrementAndGet() <= 0) {
            refCounts.remove(sticker)
            activeStickers.remove(sticker)
        }
    }

    private fun scheduleStart() {
        if (isRunning) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startInternal()
        } else {
            mainHandler.post { startInternal() }
        }
    }

    private fun startInternal() {
        if (isRunning || activeStickers.isEmpty()) return
        isRunning = true
        if (choreographer == null) {
            choreographer = Choreographer.getInstance()
        }
        choreographer?.removeFrameCallback(frameCallback)
        choreographer?.postFrameCallback(frameCallback)
    }
}
