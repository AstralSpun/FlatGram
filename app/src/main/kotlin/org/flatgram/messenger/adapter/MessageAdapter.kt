package org.flatgram.messenger.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.flatgram.messenger.R
import org.flatgram.messenger.databinding.ItemMessageAnimatedEmojiBinding
import org.flatgram.messenger.databinding.ItemMessageTextBinding
import org.flatgram.messenger.td.MessageBubbleGroupPosition
import org.flatgram.messenger.td.MessageContentUi
import org.flatgram.messenger.td.MessageListItem
import org.flatgram.messenger.td.MessageSendStatus
import org.flatgram.messenger.td.displayImagePath
import org.flatgram.messenger.td.visibleFileIdsToLoad
import java.util.Locale

class MessageAdapter(
    private val onAvatarVisible: (MessageListItem) -> Unit,
    private val onMediaVisible: (MessageListItem, List<Int>) -> Unit
) : ListAdapter<MessageListItem, MessageAdapter.MessageViewHolder>(DiffCallback) {

    private val requestedAvatarKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val requestedMediaKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).content is MessageContentUi.AnimatedEmoji) {
            VIEW_TYPE_ANIMATED_EMOJI
        } else {
            VIEW_TYPE_DEFAULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ANIMATED_EMOJI) {
            AnimatedEmojiMessageViewHolder(
                ItemMessageAnimatedEmojiBinding.inflate(inflater, parent, false),
                requestedAvatarKeys,
                requestedMediaKeys,
                onMediaVisible,
                onAvatarVisible
            )
        } else {
            BubbleMessageViewHolder(
                ItemMessageTextBinding.inflate(inflater, parent, false),
                requestedAvatarKeys,
                requestedMediaKeys,
                onMediaVisible,
                onAvatarVisible
            )
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: MessageViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.attach()
    }

    override fun onViewDetachedFromWindow(holder: MessageViewHolder) {
        holder.detach()
        super.onViewDetachedFromWindow(holder)
    }

    abstract class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(item: MessageListItem)
        open fun attach() = Unit
        open fun detach() = Unit
        open fun recycle() = Unit
    }

    private class BubbleMessageViewHolder(
        private val binding: ItemMessageTextBinding,
        private val requestedAvatarKeys: MutableSet<String>,
        private val requestedMediaKeys: MutableSet<String>,
        private val onMediaVisible: (MessageListItem, List<Int>) -> Unit,
        private val onAvatarVisible: (MessageListItem) -> Unit
    ) : MessageViewHolder(binding.root) {

        private var boundTopGapResId: Int = 0
        private var boundIsOutgoing: Boolean? = null
        private var boundBackgroundResId: Int = 0
        private var boundTextColorAttr: Int = 0

        override fun bind(item: MessageListItem) {
            bindItemSpacing(item)
            bindBubblePosition(item)
            binding.messageAvatar.visibility = if (item.showAvatar) View.VISIBLE else View.INVISIBLE
            if (item.showAvatar) {
                val avatarKey = "${item.senderId}:${item.avatarFileId ?: 0}"
                if (item.avatarPath.isNullOrBlank() && requestedAvatarKeys.add(avatarKey)) {
                    onAvatarVisible(item)
                }
                AvatarBinder.bind(
                    view = binding.messageAvatar,
                    title = item.senderName.ifBlank { "#" },
                    avatarPath = item.avatarPath,
                    placeholderBackground = R.drawable.bg_message_avatar
                )
            }
            binding.messageSender.isVisible = item.shouldShowSender()
            binding.messageSender.text = item.senderName
            bindContent(item)
            binding.messageTime.text = item.time
            binding.messageSendState.text = item.sendStateText()
            binding.messageSendState.isVisible = binding.messageSendState.text.isNotBlank()
            bindBackground(item)
            bindBubbleTextColor(item)
        }

        private fun bindContent(item: MessageListItem) {
            val fileIds = item.content.visibleFileIdsToLoad()
            if (fileIds.isNotEmpty()) {
                val requestKey = "${item.chatId}:${item.id}:${fileIds.joinToString(",")}"
                if (requestedMediaKeys.add(requestKey)) {
                    onMediaVisible(item, fileIds)
                }
            }

            binding.messageMediaFrame.isVisible = false
            binding.messageFileBox.isVisible = false
            binding.messageAnimatedEmojiPlayer.isVisible = false
            binding.messageMediaImage.isVisible = true
            binding.messageText.textSize = 16f

            when (val content = item.content) {
                is MessageContentUi.Text -> {
                    binding.messageAnimatedEmojiPlayer.clear()
                    bindText(content.value)
                }

                is MessageContentUi.Photo -> bindMedia(
                    content = content,
                    placeholder = "Photo",
                    badge = "",
                    caption = content.caption
                )

                is MessageContentUi.Video -> bindMedia(
                    content = content,
                    placeholder = "Video",
                    badge = content.duration.formatDuration(),
                    caption = content.caption
                )

                is MessageContentUi.File -> bindFile(content)
                is MessageContentUi.AnimatedEmoji -> bindAnimatedEmoji(content)
                is MessageContentUi.Unsupported -> {
                    binding.messageAnimatedEmojiPlayer.clear()
                    bindText(content.label)
                }
            }
        }

        private fun bindText(text: String) {
            binding.messageText.isVisible = text.isNotBlank()
            binding.messageText.text = text
        }

        private fun bindMedia(
            content: MessageContentUi,
            placeholder: String,
            badge: String,
            caption: String
        ) {
            binding.messageAnimatedEmojiPlayer.clear()
            binding.messageAnimatedEmojiPlayer.isVisible = false
            binding.messageMediaImage.isVisible = true
            binding.messageMediaFrame.isVisible = true
            setMediaFrameSize(MEDIA_WIDTH_DP, MEDIA_HEIGHT_DP)
            val imageLoaded = MediaPreviewBinder.bind(
                view = binding.messageMediaImage,
                placeholder = binding.messageMediaPlaceholder,
                path = content.displayImagePath(),
                targetWidth = MEDIA_TARGET_WIDTH,
                targetHeight = MEDIA_TARGET_HEIGHT
            )
            binding.messageMediaPlaceholder.isVisible = !imageLoaded
            binding.messageMediaPlaceholder.text = placeholder
            binding.messageMediaBadge.text = badge
            binding.messageMediaBadge.isVisible = badge.isNotBlank()
            bindText(caption)
        }

        private fun bindFile(content: MessageContentUi.File) {
            binding.messageAnimatedEmojiPlayer.clear()
            binding.messageFileBox.isVisible = true
            binding.messageFileName.text = content.fileName.ifBlank { "File" }
            binding.messageFileMeta.text = listOf(
                content.mimeType.takeIf { it.isNotBlank() },
                content.size.formatFileSize().takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" · ")
            bindText(content.caption)
        }

        private fun bindAnimatedEmoji(content: MessageContentUi.AnimatedEmoji) {
            val hasAnimation = binding.messageAnimatedEmojiPlayer.bind(content)
            if (hasAnimation) {
                binding.messageMediaFrame.isVisible = true
                binding.messageMediaImage.isVisible = false
                binding.messageAnimatedEmojiPlayer.isVisible = true
                binding.messageMediaPlaceholder.isVisible = false
                binding.messageMediaBadge.isVisible = false
                setMediaFrameSize(EMOJI_MEDIA_SIZE_DP, EMOJI_MEDIA_SIZE_DP)
                binding.messageText.isVisible = false
                return
            }

            if (!content.thumbnailPath.isNullOrBlank()) {
                binding.messageMediaFrame.isVisible = true
                binding.messageMediaImage.isVisible = true
                binding.messageAnimatedEmojiPlayer.isVisible = false
                val imageLoaded = MediaPreviewBinder.bind(
                    view = binding.messageMediaImage,
                    placeholder = binding.messageMediaPlaceholder,
                    path = content.thumbnailPath,
                    targetWidth = EMOJI_MEDIA_TARGET_SIZE,
                    targetHeight = EMOJI_MEDIA_TARGET_SIZE
                )
                binding.messageMediaPlaceholder.isVisible = !imageLoaded
                binding.messageMediaPlaceholder.text = content.emoji.ifBlank { "Emoji" }
                binding.messageMediaBadge.isVisible = false
                setMediaFrameSize(EMOJI_MEDIA_SIZE_DP, EMOJI_MEDIA_SIZE_DP)
                binding.messageText.isVisible = false
                return
            }

            binding.messageText.textSize = 40f
            bindText(content.emoji.ifBlank { "Animated emoji" })
        }

        override fun recycle() {
            binding.messageAnimatedEmojiPlayer.clear()
        }

        private fun bindItemSpacing(item: MessageListItem) {
            val topGap = if (
                item.groupPosition == MessageBubbleGroupPosition.SINGLE ||
                item.groupPosition == MessageBubbleGroupPosition.TOP
            ) {
                R.dimen.message_group_gap
            } else {
                R.dimen.message_joined_gap
            }

            if (topGap == boundTopGapResId) return
            boundTopGapResId = topGap
            binding.root.setPadding(
                binding.root.paddingLeft,
                itemView.resources.getDimensionPixelSize(topGap),
                binding.root.paddingRight,
                binding.root.paddingBottom
            )
        }

        private fun bindBubblePosition(item: MessageListItem) {
            if (boundIsOutgoing == item.isOutgoing) return
            boundIsOutgoing = item.isOutgoing

            val params = binding.messageBubble.layoutParams as ConstraintLayout.LayoutParams
            if (item.isOutgoing) {
                params.horizontalBias = 1f
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.marginStart = 0
            } else {
                params.horizontalBias = 0f
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = R.id.messageAvatar
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.marginStart = itemView.resources.getDimensionPixelSize(R.dimen.message_avatar_gap)
            }
            binding.messageBubble.layoutParams = params
        }

        private fun backgroundFor(item: MessageListItem): Int {
            return if (item.isOutgoing) {
                when (item.groupPosition) {
                    MessageBubbleGroupPosition.SINGLE -> R.drawable.bg_message_out
                    MessageBubbleGroupPosition.TOP -> R.drawable.bg_message_out_join_next
                    MessageBubbleGroupPosition.MIDDLE -> R.drawable.bg_message_out_join_both
                    MessageBubbleGroupPosition.BOTTOM -> R.drawable.bg_message_out_join_prev
                }
            } else {
                when (item.groupPosition) {
                    MessageBubbleGroupPosition.SINGLE -> R.drawable.bg_message_in
                    MessageBubbleGroupPosition.TOP -> R.drawable.bg_message_in_join_next
                    MessageBubbleGroupPosition.MIDDLE -> R.drawable.bg_message_in_join_both
                    MessageBubbleGroupPosition.BOTTOM -> R.drawable.bg_message_in_join_prev
                }
            }
        }

        private fun bindBackground(item: MessageListItem) {
            val backgroundResId = backgroundFor(item)
            if (backgroundResId == boundBackgroundResId) return

            boundBackgroundResId = backgroundResId
            binding.messageBubble.setBackgroundResource(backgroundResId)
        }

        private fun bindBubbleTextColor(item: MessageListItem) {
            val textColorAttr = if (item.isOutgoing) {
                com.google.android.material.R.attr.colorOnPrimaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            if (textColorAttr == boundTextColorAttr) return

            boundTextColorAttr = textColorAttr
            val textColor = itemView.context.resolveColor(textColorAttr)
            binding.messageText.setTextColor(textColor)
            binding.messageTime.setTextColor(textColor)
            binding.messageSendState.setTextColor(textColor)
            binding.messageFileName.setTextColor(textColor)
        }

        private fun Int.formatDuration(): String {
            if (this <= 0) return ""
            val minutes = this / 60
            val seconds = this % 60
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }

        private fun Long.formatFileSize(): String {
            if (this <= 0L) return ""
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = this.toDouble()
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) {
                "${this} ${units[unitIndex]}"
            } else {
                String.format(Locale.US, "%.1f %s", value, units[unitIndex])
            }
        }

        private fun setMediaFrameSize(widthDp: Int, heightDp: Int) {
            val density = itemView.resources.displayMetrics.density
            val widthPx = (widthDp * density).toInt()
            val heightPx = (heightDp * density).toInt()
            val params = binding.messageMediaFrame.layoutParams
            if (params.width == widthPx && params.height == heightPx) return
            params.width = widthPx
            params.height = heightPx
            binding.messageMediaFrame.layoutParams = params
        }

        private companion object {
            const val MEDIA_WIDTH_DP = 236
            const val MEDIA_HEIGHT_DP = 172
            const val MEDIA_TARGET_WIDTH = 708
            const val MEDIA_TARGET_HEIGHT = 516
            const val EMOJI_MEDIA_SIZE_DP = 112
            const val EMOJI_MEDIA_TARGET_SIZE = 336
        }
    }

    private class AnimatedEmojiMessageViewHolder(
        private val binding: ItemMessageAnimatedEmojiBinding,
        private val requestedAvatarKeys: MutableSet<String>,
        private val requestedMediaKeys: MutableSet<String>,
        private val onMediaVisible: (MessageListItem, List<Int>) -> Unit,
        private val onAvatarVisible: (MessageListItem) -> Unit
    ) : MessageViewHolder(binding.root) {

        private var boundTopGapResId: Int = 0
        private var boundIsOutgoing: Boolean? = null

        override fun bind(item: MessageListItem) {
            bindItemSpacing(item)
            bindPosition(item)
            bindAvatar(item)
            binding.messageSender.isVisible = item.shouldShowSender()
            binding.messageSender.text = item.senderName
            binding.messageTime.text = item.time
            binding.messageSendState.text = item.sendStateText()
            binding.messageSendState.isVisible = binding.messageSendState.text.isNotBlank()
            bindAnimatedEmoji(item)
        }

        override fun attach() {
            binding.messageAnimatedEmojiPlayer.resume()
        }

        override fun detach() {
            binding.messageAnimatedEmojiPlayer.pause()
        }

        override fun recycle() {
            binding.messageAnimatedEmojiPlayer.clear()
        }

        private fun bindAvatar(item: MessageListItem) {
            binding.messageAvatar.visibility = if (item.showAvatar) View.VISIBLE else View.INVISIBLE
            if (!item.showAvatar) return

            val avatarKey = "${item.senderId}:${item.avatarFileId ?: 0}"
            if (item.avatarPath.isNullOrBlank() && requestedAvatarKeys.add(avatarKey)) {
                onAvatarVisible(item)
            }
            AvatarBinder.bind(
                view = binding.messageAvatar,
                title = item.senderName.ifBlank { "#" },
                avatarPath = item.avatarPath,
                placeholderBackground = R.drawable.bg_message_avatar
            )
        }

        private fun bindAnimatedEmoji(item: MessageListItem) {
            val content = item.content as? MessageContentUi.AnimatedEmoji ?: return
            val fileIds = content.visibleFileIdsToLoad()
            if (fileIds.isNotEmpty()) {
                val requestKey = "${item.chatId}:${item.id}:${fileIds.joinToString(",")}"
                if (requestedMediaKeys.add(requestKey)) {
                    onMediaVisible(item, fileIds)
                }
            }

            binding.messageEmojiThumbnail.isVisible = false
            binding.messageEmojiFallback.isVisible = false
            val hasAnimation = binding.messageAnimatedEmojiPlayer.bind(content)
            binding.messageAnimatedEmojiPlayer.isVisible = hasAnimation
            if (hasAnimation) return

            if (!content.thumbnailPath.isNullOrBlank()) {
                binding.messageEmojiThumbnail.isVisible = true
                val imageLoaded = MediaPreviewBinder.bind(
                    view = binding.messageEmojiThumbnail,
                    placeholder = binding.messageEmojiFallback,
                    path = content.thumbnailPath,
                    targetWidth = EMOJI_TARGET_SIZE,
                    targetHeight = EMOJI_TARGET_SIZE
                )
                binding.messageEmojiFallback.isVisible = !imageLoaded
                binding.messageEmojiFallback.text = content.emoji.ifBlank { "Emoji" }
                return
            }

            binding.messageAnimatedEmojiPlayer.clear()
            binding.messageEmojiFallback.text = content.emoji.ifBlank { "Animated emoji" }
            binding.messageEmojiFallback.isVisible = true
        }

        private fun bindItemSpacing(item: MessageListItem) {
            val topGap = if (
                item.groupPosition == MessageBubbleGroupPosition.SINGLE ||
                item.groupPosition == MessageBubbleGroupPosition.TOP
            ) {
                R.dimen.message_group_gap
            } else {
                R.dimen.message_joined_gap
            }

            if (topGap == boundTopGapResId) return
            boundTopGapResId = topGap
            binding.root.setPadding(
                binding.root.paddingLeft,
                itemView.resources.getDimensionPixelSize(topGap),
                binding.root.paddingRight,
                binding.root.paddingBottom
            )
        }

        private fun bindPosition(item: MessageListItem) {
            if (boundIsOutgoing == item.isOutgoing) return
            boundIsOutgoing = item.isOutgoing

            val params = binding.animatedEmojiContent.layoutParams as ConstraintLayout.LayoutParams
            if (item.isOutgoing) {
                params.horizontalBias = 1f
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.marginStart = 0
            } else {
                params.horizontalBias = 0f
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                params.startToEnd = R.id.messageAvatar
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.marginStart = itemView.resources.getDimensionPixelSize(R.dimen.message_avatar_gap)
            }
            binding.animatedEmojiContent.layoutParams = params
        }

        private companion object {
            const val EMOJI_TARGET_SIZE = 276
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<MessageListItem>() {
        override fun areItemsTheSame(oldItem: MessageListItem, newItem: MessageListItem): Boolean {
            return oldItem.chatId == newItem.chatId && oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageListItem, newItem: MessageListItem): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        const val VIEW_TYPE_DEFAULT = 0
        const val VIEW_TYPE_ANIMATED_EMOJI = 1
    }
}

private fun android.content.Context.resolveColor(attr: Int): Int {
    return obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0) }
}

private fun MessageListItem.shouldShowSender(): Boolean {
    if (isOutgoing || senderName.isBlank()) return false
    return groupPosition != MessageBubbleGroupPosition.MIDDLE &&
        groupPosition != MessageBubbleGroupPosition.BOTTOM
}

private fun MessageListItem.sendStateText(): String {
    if (!isOutgoing) return ""
    return when (status) {
        MessageSendStatus.SENDING -> "..."
        MessageSendStatus.FAILED -> "!"
        MessageSendStatus.NONE -> ""
    }
}
