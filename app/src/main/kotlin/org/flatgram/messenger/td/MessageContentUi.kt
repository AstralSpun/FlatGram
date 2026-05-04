package org.flatgram.messenger.td

sealed class MessageContentUi {
    data class Text(val value: String) : MessageContentUi()

    data class Photo(
        val caption: String,
        val mediaFileId: Int,
        val mediaPath: String?,
        val thumbnailFileId: Int?,
        val thumbnailPath: String?,
        val width: Int,
        val height: Int
    ) : MessageContentUi()

    data class Video(
        val caption: String,
        val mediaFileId: Int,
        val mediaPath: String?,
        val thumbnailFileId: Int?,
        val thumbnailPath: String?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val supportsStreaming: Boolean
    ) : MessageContentUi()

    data class File(
        val caption: String,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val mediaFileId: Int,
        val mediaPath: String?,
        val thumbnailFileId: Int?,
        val thumbnailPath: String?
    ) : MessageContentUi()

    data class AnimatedEmoji(
        val emoji: String,
        val stickerFileId: Int?,
        val stickerPath: String?,
        val thumbnailFileId: Int?,
        val thumbnailPath: String?,
        val format: AnimatedEmojiFormat,
        val width: Int,
        val height: Int
    ) : MessageContentUi()

    data class Unsupported(val label: String) : MessageContentUi()
}

enum class AnimatedEmojiFormat {
    TGS,
    WEBM,
    WEBP,
    UNKNOWN
}

fun MessageContentUi.previewText(): String {
    return when (this) {
        is MessageContentUi.Text -> value
        is MessageContentUi.Photo -> caption.ifBlank { "Photo" }
        is MessageContentUi.Video -> caption.ifBlank { "Video" }
        is MessageContentUi.File -> caption.ifBlank { fileName.ifBlank { "File" } }
        is MessageContentUi.AnimatedEmoji -> emoji.ifBlank { "Animated emoji" }
        is MessageContentUi.Unsupported -> label
    }
}

fun MessageContentUi.displayImagePath(): String? {
    return when (this) {
        is MessageContentUi.Photo -> mediaPath ?: thumbnailPath
        is MessageContentUi.Video -> thumbnailPath
        is MessageContentUi.File -> thumbnailPath
        is MessageContentUi.AnimatedEmoji -> stickerPath ?: thumbnailPath
        else -> null
    }
}

fun MessageContentUi.visibleFileIdsToLoad(): List<Int> {
    return when (this) {
        is MessageContentUi.Photo -> listOfNotNull(
            thumbnailFileId?.takeIf { thumbnailPath.isNullOrBlank() },
            mediaFileId.takeIf { thumbnailFileId == null && it != 0 && mediaPath.isNullOrBlank() }
        )

        is MessageContentUi.Video -> listOfNotNull(
            thumbnailFileId?.takeIf { thumbnailPath.isNullOrBlank() }
        )

        is MessageContentUi.File -> listOfNotNull(
            thumbnailFileId?.takeIf { thumbnailPath.isNullOrBlank() }
        )

        is MessageContentUi.AnimatedEmoji -> listOfNotNull(
            stickerFileId?.takeIf { stickerPath.isNullOrBlank() },
            thumbnailFileId?.takeIf { stickerPath.isNullOrBlank() && thumbnailPath.isNullOrBlank() }
        )

        else -> emptyList()
    }
}

fun MessageContentUi.fileIds(): Set<Int> {
    return when (this) {
        is MessageContentUi.Photo -> listOfNotNull(mediaFileId.takeIf { it != 0 }, thumbnailFileId).toSet()
        is MessageContentUi.Video -> listOfNotNull(mediaFileId.takeIf { it != 0 }, thumbnailFileId).toSet()
        is MessageContentUi.File -> listOfNotNull(mediaFileId.takeIf { it != 0 }, thumbnailFileId).toSet()
        is MessageContentUi.AnimatedEmoji -> listOfNotNull(stickerFileId, thumbnailFileId).toSet()
        else -> emptySet()
    }
}

fun MessageContentUi.withResolvedFilePaths(): MessageContentUi {
    return when (this) {
        is MessageContentUi.Photo -> copy(
            mediaPath = mediaPath ?: TdEntityCache.fileInfo(mediaFileId).path,
            thumbnailPath = thumbnailPath ?: thumbnailFileId?.let { TdEntityCache.fileInfo(it).path }
        )

        is MessageContentUi.Video -> copy(
            mediaPath = mediaPath ?: TdEntityCache.fileInfo(mediaFileId).path,
            thumbnailPath = thumbnailPath ?: thumbnailFileId?.let { TdEntityCache.fileInfo(it).path }
        )

        is MessageContentUi.File -> copy(
            mediaPath = mediaPath ?: TdEntityCache.fileInfo(mediaFileId).path,
            thumbnailPath = thumbnailPath ?: thumbnailFileId?.let { TdEntityCache.fileInfo(it).path }
        )

        is MessageContentUi.AnimatedEmoji -> copy(
            stickerPath = stickerPath ?: stickerFileId?.let { TdEntityCache.fileInfo(it).path },
            thumbnailPath = thumbnailPath ?: thumbnailFileId?.let { TdEntityCache.fileInfo(it).path }
        )

        else -> this
    }
}
