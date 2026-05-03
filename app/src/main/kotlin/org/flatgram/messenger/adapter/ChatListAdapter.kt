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

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    fun preloadAvatars(limit: Int) {
        currentList
            .asSequence()
            .take(limit)
            .mapNotNull { it.avatarPath }
            .forEach(AvatarBinder::preload)
    }

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

    override fun onBindViewHolder(
        holder: ChatViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val payload = payloads.filterIsInstance<ChatPayload>().firstOrNull()
        if (payload == null) {
            holder.bind(getItem(position))
        } else {
            holder.bind(getItem(position), payload)
        }
    }

    class ChatViewHolder(
        private val binding: ItemChatBinding,
        private val requestedAvatarKeys: MutableSet<String>,
        private val onChatClick: (ChatListItem) -> Unit,
        private val onAvatarVisible: (ChatListItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundAvatarPath: String? = null
        private var boundAvatarTitle: String = ""
        private var boundTitle: String = ""
        private var boundLastMessage: String = ""
        private var boundTime: String = ""
        private var boundUnreadCount: Int = -1
        private var boundPinned: Boolean = false

        fun bind(item: ChatListItem) {
            val avatarKey = "${item.id}:${item.avatarFileId ?: 0}"
            val avatarChanged = item.avatarPath != boundAvatarPath ||
                (item.avatarPath.isNullOrBlank() && item.title != boundAvatarTitle)
            if (avatarChanged && item.avatarPath.isNullOrBlank() && requestedAvatarKeys.add(avatarKey)) {
                onAvatarVisible(item)
            }
            if (avatarChanged) {
                AvatarBinder.bind(
                    view = binding.avatarText,
                    title = item.title,
                    avatarPath = item.avatarPath,
                    placeholderBackground = R.drawable.bg_chat_avatar
                )
                boundAvatarPath = item.avatarPath
                boundAvatarTitle = item.title
            }

            if (item.title != boundTitle) {
                boundTitle = item.title
                binding.titleText.text = item.title
            }
            if (item.lastMessage != boundLastMessage) {
                boundLastMessage = item.lastMessage
                binding.messageText.text = item.lastMessage
            }
            if (item.time != boundTime) {
                boundTime = item.time
                binding.timeText.text = item.time
            }
            if (item.isPinned != boundPinned) {
                boundPinned = item.isPinned
                binding.pinnedText.isVisible = item.isPinned
            }
            if (item.unreadCount != boundUnreadCount) {
                boundUnreadCount = item.unreadCount
                binding.unreadText.isVisible = item.unreadCount > 0
                binding.unreadText.text = item.unreadCount.coerceAtMost(999).toString()
            }
            binding.root.setOnClickListener { onChatClick(item) }
        }

        fun bind(item: ChatListItem, payload: ChatPayload) {
            if (payload.avatarChanged || payload.titleChanged) {
                bind(item)
                return
            }

            if (payload.lastMessageChanged) {
                boundLastMessage = item.lastMessage
                binding.messageText.text = item.lastMessage
            }
            if (payload.timeChanged) {
                boundTime = item.time
                binding.timeText.text = item.time
            }
            if (payload.pinnedChanged) {
                boundPinned = item.isPinned
                binding.pinnedText.isVisible = item.isPinned
            }
            if (payload.unreadChanged) {
                boundUnreadCount = item.unreadCount
                binding.unreadText.isVisible = item.unreadCount > 0
                binding.unreadText.text = item.unreadCount.coerceAtMost(999).toString()
            }
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

        override fun getChangePayload(oldItem: ChatListItem, newItem: ChatListItem): Any {
            return ChatPayload(
                titleChanged = oldItem.title != newItem.title,
                avatarChanged = oldItem.avatarFileId != newItem.avatarFileId ||
                    oldItem.avatarPath != newItem.avatarPath,
                lastMessageChanged = oldItem.lastMessage != newItem.lastMessage,
                timeChanged = oldItem.time != newItem.time,
                unreadChanged = oldItem.unreadCount != newItem.unreadCount,
                pinnedChanged = oldItem.isPinned != newItem.isPinned
            )
        }
    }

    data class ChatPayload(
        val titleChanged: Boolean,
        val avatarChanged: Boolean,
        val lastMessageChanged: Boolean,
        val timeChanged: Boolean,
        val unreadChanged: Boolean,
        val pinnedChanged: Boolean
    )
}
