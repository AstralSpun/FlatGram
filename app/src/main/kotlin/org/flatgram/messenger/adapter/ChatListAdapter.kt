package org.flatgram.messenger.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.flatgram.messenger.R
import org.flatgram.messenger.databinding.ItemChatBinding
import org.flatgram.messenger.td.ChatListItem
import java.util.concurrent.ConcurrentHashMap

class ChatListAdapter(
    private val onChatClick: (ChatListItem) -> Unit,
    private val onAvatarVisible: (ChatListItem) -> Unit
) : ListAdapter<ChatListItem, ChatListAdapter.ChatViewHolder>(DiffCallback) {

    private val requestedAvatarKeys = ConcurrentHashMap.newKeySet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding, requestedAvatarKeys, onChatClick, onAvatarVisible)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChatViewHolder(
        private val binding: ItemChatBinding,
        private val requestedAvatarKeys: MutableSet<String>,
        private val onChatClick: (ChatListItem) -> Unit,
        private val onAvatarVisible: (ChatListItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatListItem) {
            val avatarKey = "${item.id}:${item.avatarFileId ?: 0}"
            if (item.avatarPath.isNullOrBlank() && requestedAvatarKeys.add(avatarKey)) {
                onAvatarVisible(item)
            }
            AvatarBinder.bind(
                view = binding.avatarText,
                title = item.title,
                avatarPath = item.avatarPath,
                placeholderBackground = R.drawable.bg_chat_avatar
            )
            binding.titleText.text = item.title
            binding.messageText.text = item.lastMessage
            binding.timeText.text = item.time
            binding.pinnedText.isVisible = item.isPinned
            binding.unreadText.isVisible = item.unreadCount > 0
            binding.unreadText.text = item.unreadCount.coerceAtMost(999).toString()
            binding.root.setOnClickListener { onChatClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatListItem>() {
        override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem == newItem
        }
    }
}
