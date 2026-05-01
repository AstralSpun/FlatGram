package org.flatgram.messenger.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.flatgram.messenger.databinding.ItemMessageInBinding
import org.flatgram.messenger.databinding.ItemMessageOutBinding
import org.flatgram.messenger.td.MessageListItem
import org.flatgram.messenger.td.MessageSendStatus

class MessageAdapter : ListAdapter<MessageListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isOutgoing) VIEW_TYPE_OUT else VIEW_TYPE_IN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_OUT -> OutViewHolder(ItemMessageOutBinding.inflate(inflater, parent, false))
            else -> InViewHolder(ItemMessageInBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is InViewHolder -> holder.bind(item)
            is OutViewHolder -> holder.bind(item)
        }
    }

    private class InViewHolder(
        private val binding: ItemMessageInBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageListItem) {
            binding.messageText.text = item.text
            binding.timeText.text = item.time
        }
    }

    private class OutViewHolder(
        private val binding: ItemMessageOutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageListItem) {
            binding.messageText.text = item.text
            binding.timeText.text = item.time
            binding.statusText.isVisible = item.status != MessageSendStatus.NONE
            binding.statusText.text = when (item.status) {
                MessageSendStatus.SENDING -> "Sending"
                MessageSendStatus.FAILED -> "Failed"
                MessageSendStatus.NONE -> ""
            }
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
        const val VIEW_TYPE_IN = 1
        const val VIEW_TYPE_OUT = 2
    }
}
