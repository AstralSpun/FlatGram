package org.flatgram.messenger.sticker

import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

object StickerRegistry {
    private data class CacheKey(
        val path: String,
        val width: Int,
        val height: Int
    )

    private class SharedController(
        val controller: StickerController,
        var refCount: Int = 1
    )

    private class PendingSlot(
        val key: CacheKey
    ) {
        @Volatile var controller: StickerController? = null
        @Volatile var failure: Throwable? = null
        @Volatile var refCount: Int = 1
    }

    private val cache = HashMap<CacheKey, SharedController>()
    private val pending = HashMap<CacheKey, PendingSlot>()
    private val lock = Object()

    fun acquireTgs(path: String, width: Int, height: Int): StickerController {
        val key = CacheKey(path, width, height)

        // Fast path: already cached.
        synchronized(lock) {
            cache[key]?.let { shared ->
                shared.refCount++
                return shared.controller
            }

            // Another thread is already loading the same key — wait for it.
            pending[key]?.let { slot ->
                slot.refCount++
                while (slot.controller == null && slot.failure == null) {
                    (lock as java.lang.Object).wait()
                }
                slot.failure?.let { throw it }
                val controller = slot.controller!!
                slot.refCount--
                if (slot.refCount == 0 && pending[key] === slot) {
                    pending.remove(key)
                }
                return controller
            }

            // Mark this key as being loaded so other threads can wait instead of duplicating IO/decode.
            pending[key] = PendingSlot(key)
        }

        // Heavy work — file read + gunzip + JSON parse + rlottie load — done OUTSIDE the registry lock
        // so other (path,size) keys can be loaded in parallel and the cache hot path doesn't block.
        var controller: StickerController? = null
        var failure: Throwable? = null
        try {
            val json = readTgsJson(File(path))
            controller = StickerController(json, width, height)
        } catch (t: Throwable) {
            failure = t
        }

        synchronized(lock) {
            val slot = pending.remove(key)
            if (controller != null) {
                cache[key] = SharedController(controller, refCount = 1 + (slot?.refCount?.minus(1) ?: 0))
                slot?.controller = controller
            } else {
                slot?.failure = failure ?: RuntimeException("StickerController construction failed")
            }
            (lock as java.lang.Object).notifyAll()
        }

        if (controller != null) return controller
        throw failure ?: RuntimeException("StickerController construction failed")
    }

    fun release(path: String, width: Int, height: Int) {
        val key = CacheKey(path, width, height)
        var toClose: StickerController? = null
        synchronized(lock) {
            val shared = cache[key] ?: return
            shared.refCount--
            if (shared.refCount <= 0) {
                cache.remove(key)
                toClose = shared.controller
            }
        }
        toClose?.close()
    }

    private fun readTgsJson(file: File): String {
        FileInputStream(file).use { input ->
            // Peek the first 2 bytes to detect gzip without reading the whole file twice.
            val header = ByteArray(2)
            val read = input.read(header)
            if (read == 2 && header[0] == GZIP_MAGIC_0 && header[1] == GZIP_MAGIC_1) {
                // Re-open and stream-decompress to avoid keeping the compressed bytes in memory.
                FileInputStream(file).use { src ->
                    GZIPInputStream(src).use { gz ->
                        return gz.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
            // Plain JSON: read remainder.
            val rest = input.readBytes()
            val full = ByteArray(read + rest.size)
            System.arraycopy(header, 0, full, 0, read)
            System.arraycopy(rest, 0, full, read, rest.size)
            return full.toString(Charsets.UTF_8)
        }
    }

    private const val GZIP_MAGIC_0 = 0x1f.toByte()
    private const val GZIP_MAGIC_1 = 0x8b.toByte()
}
