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
import org.flatgram.messenger.databinding.ItemMessageTextBinding
import org.flatgram.messenger.td.MessageBubbleGroupPosition
import org.flatgram.messenger.td.MessageListItem
import org.flatgram.messenger.td.MessageSendStatus

class MessageAdapter : ListAdapter<MessageListItem, MessageAdapter.MessageViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return MessageViewHolder(ItemMessageTextBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemMessageTextBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageListItem) {
            bindItemSpacing(item)
            bindBubblePosition(item)
            binding.messageAvatar.visibility = if (item.showAvatar) View.VISIBLE else View.INVISIBLE
            binding.messageAvatar.text = item.senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            binding.messageSender.isVisible = shouldShowSender(item)
            binding.messageSender.text = item.senderName
            binding.messageText.text = item.text
            binding.messageTime.text = item.time
            binding.messageSendState.text = sendStateText(item)
            binding.messageSendState.isVisible = binding.messageSendState.text.isNotBlank()
            binding.messageBubble.setBackgroundResource(backgroundFor(item))
            bindBubbleTextColor(item)
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

            binding.root.setPadding(
                binding.root.paddingLeft,
                itemView.resources.getDimensionPixelSize(topGap),
                binding.root.paddingRight,
                binding.root.paddingBottom
            )
        }

        private fun bindBubblePosition(item: MessageListItem) {
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

        private fun shouldShowSender(item: MessageListItem): Boolean {
            if (item.isOutgoing || item.senderName.isBlank()) return false
            return item.groupPosition != MessageBubbleGroupPosition.MIDDLE &&
                item.groupPosition != MessageBubbleGroupPosition.BOTTOM
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

        private fun sendStateText(item: MessageListItem): String {
            if (!item.isOutgoing) return ""
            return when (item.status) {
                MessageSendStatus.SENDING -> "..."
                MessageSendStatus.FAILED -> "!"
                MessageSendStatus.NONE -> ""
            }
        }

        private fun bindBubbleTextColor(item: MessageListItem) {
            val textColorAttr = if (item.isOutgoing) {
                com.google.android.material.R.attr.colorOnPrimaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            val textColor = itemView.context.resolveColor(textColorAttr)
            binding.messageText.setTextColor(textColor)
            binding.messageTime.setTextColor(textColor)
            binding.messageSendState.setTextColor(textColor)
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
}

private fun android.content.Context.resolveColor(attr: Int): Int {
    return obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0) }
}
