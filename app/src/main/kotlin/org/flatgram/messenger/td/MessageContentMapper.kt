package org.flatgram.messenger.td

import org.drinkless.tdlib.TdApi

object MessageContentMapper {

    fun map(content: TdApi.MessageContent?): MessageContentUi {
        return when (content) {
            null -> MessageContentUi.Text("")
            is TdApi.MessageText -> MessageContentUi.Text(content.text.text)
            is TdApi.MessagePhoto -> mapPhoto(content)
            is TdApi.MessageVideo -> mapVideo(content)
            is TdApi.MessageDocument -> mapDocument(content)
            is TdApi.MessageAnimatedEmoji -> mapAnimatedEmoji(content)
            else -> MessageContentUi.Unsupported(MessageContentFormatter.format(content))
        }
    }

    private fun mapPhoto(content: TdApi.MessagePhoto): MessageContentUi {
        val sizes = content.photo.sizes ?: emptyArray()
        val photoSize = sizes.preferredPhotoSize()
        val thumbnailSize = sizes.preferredThumbnailSize()
        val mediaFile = photoSize?.photo?.cachedFile()
        val thumbnailFile = thumbnailSize?.photo?.cachedFile()

        return MessageContentUi.Photo(
            caption = content.caption.text,
            mediaFileId = mediaFile?.id ?: 0,
            mediaPath = mediaFile.localPath(),
            thumbnailFileId = thumbnailFile?.id,
            thumbnailPath = thumbnailFile.localPath(),
            width = photoSize?.width ?: 0,
            height = photoSize?.height ?: 0
        )
    }

    private fun mapVideo(content: TdApi.MessageVideo): MessageContentUi {
        val video = content.video
        val mediaFile = video.video.cachedFile()
        val thumbnailFile = video.thumbnail?.file?.cachedFile()

        return MessageContentUi.Video(
            caption = content.caption.text,
            mediaFileId = mediaFile?.id ?: 0,
            mediaPath = mediaFile.localPath(),
            thumbnailFileId = thumbnailFile?.id,
            thumbnailPath = thumbnailFile.localPath(),
            width = video.width,
            height = video.height,
            duration = video.duration,
            supportsStreaming = video.supportsStreaming
        )
    }

    private fun mapDocument(content: TdApi.MessageDocument): MessageContentUi {
        val document = content.document
        val mediaFile = document.document.cachedFile()
        val thumbnailFile = document.thumbnail?.file?.cachedFile()

        return MessageContentUi.File(
            caption = content.caption.text,
            fileName = document.fileName.orEmpty(),
            mimeType = document.mimeType.orEmpty(),
            size = mediaFile?.size ?: 0L,
            mediaFileId = mediaFile?.id ?: 0,
            mediaPath = mediaFile.localPath(),
            thumbnailFileId = thumbnailFile?.id,
            thumbnailPath = thumbnailFile.localPath()
        )
    }

    private fun mapAnimatedEmoji(content: TdApi.MessageAnimatedEmoji): MessageContentUi {
        val sticker = content.animatedEmoji.sticker
        val stickerFile = sticker?.sticker?.cachedFile()
        val thumbnailFile = sticker?.thumbnail?.file?.cachedFile()

        return MessageContentUi.AnimatedEmoji(
            emoji = content.emoji.orEmpty(),
            stickerFileId = stickerFile?.id,
            stickerPath = stickerFile.localPath(),
            thumbnailFileId = thumbnailFile?.id,
            thumbnailPath = thumbnailFile.localPath(),
            format = sticker?.format.toAnimatedEmojiFormat(),
            width = content.animatedEmoji.stickerWidth,
            height = content.animatedEmoji.stickerHeight
        )
    }

    private fun TdApi.StickerFormat?.toAnimatedEmojiFormat(): AnimatedEmojiFormat {
        return when (this) {
            is TdApi.StickerFormatTgs -> AnimatedEmojiFormat.TGS
            is TdApi.StickerFormatWebm -> AnimatedEmojiFormat.WEBM
            is TdApi.StickerFormatWebp -> AnimatedEmojiFormat.WEBP
            else -> AnimatedEmojiFormat.UNKNOWN
        }
    }

    private fun Array<TdApi.PhotoSize>.preferredPhotoSize(): TdApi.PhotoSize? {
        return firstOrNull { it.type == "x" }
            ?: firstOrNull { it.type == "y" }
            ?: firstOrNull { it.type == "m" }
            ?: getOrNull(size / 2)
            ?: lastOrNull()
    }

    private fun Array<TdApi.PhotoSize>.preferredThumbnailSize(): TdApi.PhotoSize? {
        return firstOrNull { it.type == "m" }
            ?: firstOrNull { it.type == "s" }
            ?: firstOrNull()
    }

    private fun TdApi.File?.cachedFile(): TdApi.File? {
        if (this == null || id == 0) return null
        TdEntityCache.putFile(this)
        return this
    }

    private fun TdApi.File?.localPath(): String? {
        if (this == null || id == 0) return null
        return TdEntityCache.fileInfo(id).path ?: local?.path?.takeIf {
            local?.isDownloadingCompleted == true && it.isNotBlank()
        }
    }
}
