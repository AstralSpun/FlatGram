package org.flatgram.messenger.td

import org.json.JSONObject

object MessageContentSnapshot {

    fun encode(content: MessageContentUi): String {
        val json = JSONObject()
        when (content) {
            is MessageContentUi.Text -> json.put("type", "text").put("value", content.value)
            is MessageContentUi.Photo -> json
                .put("type", "photo")
                .put("caption", content.caption)
                .put("mediaFileId", content.mediaFileId)
                .put("mediaPath", content.mediaPath)
                .put("thumbnailFileId", content.thumbnailFileId)
                .put("thumbnailPath", content.thumbnailPath)
                .put("width", content.width)
                .put("height", content.height)

            is MessageContentUi.Video -> json
                .put("type", "video")
                .put("caption", content.caption)
                .put("mediaFileId", content.mediaFileId)
                .put("mediaPath", content.mediaPath)
                .put("thumbnailFileId", content.thumbnailFileId)
                .put("thumbnailPath", content.thumbnailPath)
                .put("width", content.width)
                .put("height", content.height)
                .put("duration", content.duration)
                .put("supportsStreaming", content.supportsStreaming)

            is MessageContentUi.File -> json
                .put("type", "file")
                .put("caption", content.caption)
                .put("fileName", content.fileName)
                .put("mimeType", content.mimeType)
                .put("size", content.size)
                .put("mediaFileId", content.mediaFileId)
                .put("mediaPath", content.mediaPath)
                .put("thumbnailFileId", content.thumbnailFileId)
                .put("thumbnailPath", content.thumbnailPath)

            is MessageContentUi.AnimatedEmoji -> json
                .put("type", "animated_emoji")
                .put("emoji", content.emoji)
                .put("stickerFileId", content.stickerFileId)
                .put("stickerPath", content.stickerPath)
                .put("thumbnailFileId", content.thumbnailFileId)
                .put("thumbnailPath", content.thumbnailPath)
                .put("format", content.format.name)
                .put("width", content.width)
                .put("height", content.height)

            is MessageContentUi.Unsupported -> json
                .put("type", "unsupported")
                .put("label", content.label)
        }
        return json.toString()
    }

    fun decode(raw: String?, fallbackText: String): MessageContentUi {
        if (raw.isNullOrBlank()) return MessageContentUi.Text(fallbackText)

        return runCatching {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "text" -> MessageContentUi.Text(json.optString("value", fallbackText))
                "photo" -> MessageContentUi.Photo(
                    caption = json.optString("caption"),
                    mediaFileId = json.optInt("mediaFileId"),
                    mediaPath = json.optNullableString("mediaPath"),
                    thumbnailFileId = json.optNullableInt("thumbnailFileId"),
                    thumbnailPath = json.optNullableString("thumbnailPath"),
                    width = json.optInt("width"),
                    height = json.optInt("height")
                )

                "video" -> MessageContentUi.Video(
                    caption = json.optString("caption"),
                    mediaFileId = json.optInt("mediaFileId"),
                    mediaPath = json.optNullableString("mediaPath"),
                    thumbnailFileId = json.optNullableInt("thumbnailFileId"),
                    thumbnailPath = json.optNullableString("thumbnailPath"),
                    width = json.optInt("width"),
                    height = json.optInt("height"),
                    duration = json.optInt("duration"),
                    supportsStreaming = json.optBoolean("supportsStreaming")
                )

                "file" -> MessageContentUi.File(
                    caption = json.optString("caption"),
                    fileName = json.optString("fileName"),
                    mimeType = json.optString("mimeType"),
                    size = json.optLong("size"),
                    mediaFileId = json.optInt("mediaFileId"),
                    mediaPath = json.optNullableString("mediaPath"),
                    thumbnailFileId = json.optNullableInt("thumbnailFileId"),
                    thumbnailPath = json.optNullableString("thumbnailPath")
                )

                "animated_emoji" -> MessageContentUi.AnimatedEmoji(
                    emoji = json.optString("emoji"),
                    stickerFileId = json.optNullableInt("stickerFileId"),
                    stickerPath = json.optNullableString("stickerPath"),
                    thumbnailFileId = json.optNullableInt("thumbnailFileId"),
                    thumbnailPath = json.optNullableString("thumbnailPath"),
                    format = json.optAnimatedEmojiFormat("format"),
                    width = json.optInt("width"),
                    height = json.optInt("height")
                )

                "unsupported" -> MessageContentUi.Unsupported(json.optString("label", fallbackText))
                else -> MessageContentUi.Text(fallbackText)
            }
        }.getOrElse {
            MessageContentUi.Text(fallbackText)
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optInt(name).takeIf { it != 0 }
    }

    private fun JSONObject.optAnimatedEmojiFormat(name: String): AnimatedEmojiFormat {
        val raw = optString(name).takeIf { it.isNotBlank() } ?: return AnimatedEmojiFormat.UNKNOWN
        return runCatching { AnimatedEmojiFormat.valueOf(raw) }.getOrDefault(AnimatedEmojiFormat.UNKNOWN)
    }
}
