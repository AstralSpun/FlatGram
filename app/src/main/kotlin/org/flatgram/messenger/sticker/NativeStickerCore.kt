package org.flatgram.messenger.sticker

import android.graphics.Bitmap
import android.util.Log

object NativeStickerCore {
    private const val TAG = "FlatGramStickerNative"

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("flatgram_sticker")
    }.onFailure { error ->
        Log.e(TAG, "Failed to load native sticker renderer", error)
    }.isSuccess

    external fun createLottieHandle(json: String): Long
    external fun destroyLottieHandle(ptr: Long)
    external fun getLottieFrameCount(ptr: Long): Int
    external fun getLottieFrameRate(ptr: Long): Float
    external fun getLottieSize(ptr: Long, outArray: IntArray)
    external fun prepareLottieRendering(ptr: Long, width: Int, height: Int)
    external fun renderLottieFrame(ptr: Long, frame: Int, bitmap: Bitmap): Boolean
    external fun renderLottieFrameAsync(ptr: Long, bitmap: Bitmap, frame: Int, listener: Any): Boolean
}
