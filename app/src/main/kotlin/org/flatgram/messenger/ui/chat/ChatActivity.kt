package org.flatgram.messenger.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.flatgram.messenger.adapter.MessageAdapter
import org.flatgram.messenger.databinding.ActivityChatBinding
import org.flatgram.messenger.td.MessageListItem
import org.flatgram.messenger.td.TdMessageRepository

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private val chatListener = object : TdMessageRepository.ChatListener {
        override fun onMessagesChanged(messages: List<MessageListItem>) {
            val previousMessageCount = lastMessageCount
            val shouldScrollToBottom = lastMessageCount == 0 || isNearBottom()
            val scrollGeneration = userScrollGeneration
            lastMessageCount = messages.size

            submitMessages(messages) {
                binding.progress.isVisible = false
                binding.emptyText.isVisible = messages.isEmpty()
                binding.emptyText.text = if (messages.isEmpty()) "No messages" else ""

                if (
                    shouldScrollToBottom &&
                    messages.isNotEmpty() &&
                    canAutoScrollToBottom(previousMessageCount, scrollGeneration)
                ) {
                    binding.messageRecycler.scrollToPosition(NEWEST_MESSAGE_POSITION)
                }
                markVisibleMessagesRead(messages)
            }
        }

        override fun onMessageError(message: String) {
            binding.progress.isVisible = false
            Toast.makeText(this@ChatActivity, message, Toast.LENGTH_SHORT).show()
            if (adapter.itemCount == 0) {
                binding.emptyText.isVisible = true
                binding.emptyText.text = message
            }
        }
    }

    private var chatId: Long = 0L
    private var lastMessageCount = 0
    private var lastSubmittedMessages: List<MessageListItem> = emptyList()
    private var userScrollGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty()
        if (chatId == 0L) {
            finish()
            return
        }

        binding.toolbar.title = chatTitle.ifBlank { "Chat" }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = MessageAdapter(
            onAvatarVisible = { message ->
                TdMessageRepository.requestAvatar(message.chatId, message.senderId, message.avatarFileId)
            }
        )
        layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, true)

        binding.messageRecycler.layoutManager = layoutManager
        binding.messageRecycler.adapter = adapter
        binding.messageRecycler.itemAnimator = null
        binding.messageRecycler.setItemViewCacheSize(MESSAGE_ROW_CACHE_SIZE)
        binding.progress.isVisible = false
        binding.emptyText.isVisible = false
        renderInitialSnapshot()
        binding.messageRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrollGeneration++
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (shouldLoadOlderMessages()) {
                    TdMessageRepository.loadOlder(chatId)
                }
            }
        })

        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        TdMessageRepository.addChatListener(chatId, chatListener)
        TdMessageRepository.openChat(chatId)
    }

    override fun onDestroy() {
        TdMessageRepository.removeChatListener(chatId, chatListener)
        if (chatId != 0L) {
            TdMessageRepository.closeChat(chatId)
        }
        super.onDestroy()
    }

    private fun sendMessage() {
        val text = binding.messageEdit.text?.toString().orEmpty()
        if (text.isBlank()) return

        binding.messageEdit.text = null
        TdMessageRepository.sendText(chatId, text)
    }

    private fun renderInitialSnapshot() {
        val messages = TdMessageRepository.currentMessages(chatId)
        if (messages.isEmpty()) return

        lastMessageCount = messages.size
        submitMessages(messages)
        binding.messageRecycler.scrollToPosition(NEWEST_MESSAGE_POSITION)
        markVisibleMessagesRead(messages)
    }

    private fun submitMessages(
        messages: List<MessageListItem>,
        commitCallback: () -> Unit = {}
    ) {
        if (messages == lastSubmittedMessages) {
            commitCallback()
            return
        }

        lastSubmittedMessages = messages
        adapter.submitList(messages, commitCallback)
    }

    private fun canAutoScrollToBottom(previousMessageCount: Int, scrollGeneration: Int): Boolean {
        if (userScrollGeneration != scrollGeneration) return false
        if (binding.messageRecycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return false
        return previousMessageCount == 0 || isNearBottom()
    }

    private fun isNearBottom(): Boolean {
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        return firstVisible == RecyclerView.NO_POSITION || firstVisible <= NEWEST_MESSAGE_THRESHOLD
    }

    private fun shouldLoadOlderMessages(): Boolean {
        if (adapter.itemCount == 0) return false
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible == RecyclerView.NO_POSITION) return false
        val prefetchStart = (adapter.itemCount - OLDER_MESSAGES_PREFETCH_DISTANCE).coerceAtLeast(0)
        return lastVisible >= prefetchStart
    }

    private fun markVisibleMessagesRead(messages: List<MessageListItem>) {
        if (messages.isEmpty()) return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) {
            TdMessageRepository.markVisibleMessagesRead(chatId, messages)
            return
        }

        val fromIndex = minOf(firstVisible, lastVisible).coerceAtLeast(0)
        val toIndex = maxOf(firstVisible, lastVisible).coerceAtMost(messages.lastIndex)
        if (fromIndex > toIndex) return

        TdMessageRepository.markVisibleMessagesRead(chatId, messages.subList(fromIndex, toIndex + 1))
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_CHAT_TITLE = "chat_title"
        private const val NEWEST_MESSAGE_POSITION = 0
        private const val NEWEST_MESSAGE_THRESHOLD = 3
        private const val OLDER_MESSAGES_PREFETCH_DISTANCE = 250
        private const val MESSAGE_ROW_CACHE_SIZE = 32

        fun createIntent(context: Context, chatId: Long, chatTitle: String): Intent {
            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_CHAT_TITLE, chatTitle)
        }
    }
}
