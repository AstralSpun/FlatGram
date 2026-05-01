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

    private var chatId: Long = 0L
    private var lastMessageCount = 0

    private val messageListener = object : TdMessageRepository.Listener {
        override fun onMessagesChanged(chatId: Long, messages: List<MessageListItem>) {
            if (chatId != this@ChatActivity.chatId) return

            val shouldScrollToBottom = lastMessageCount == 0 || isNearBottom()
            lastMessageCount = messages.size

            adapter.submitList(messages) {
                binding.progress.isVisible = false
                binding.emptyText.isVisible = messages.isEmpty()
                binding.emptyText.text = if (messages.isEmpty()) "No messages" else ""

                if (shouldScrollToBottom && messages.isNotEmpty()) {
                    binding.messageRecycler.scrollToPosition(messages.lastIndex)
                }
            }
        }

        override fun onMessageError(chatId: Long, message: String) {
            if (chatId != this@ChatActivity.chatId) return
            binding.progress.isVisible = false
            Toast.makeText(this@ChatActivity, message, Toast.LENGTH_SHORT).show()
            if (adapter.itemCount == 0) {
                binding.emptyText.isVisible = true
                binding.emptyText.text = message
            }
        }
    }

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

        adapter = MessageAdapter()
        layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        binding.messageRecycler.layoutManager = layoutManager
        binding.messageRecycler.adapter = adapter
        binding.messageRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findFirstVisibleItemPosition() <= 2) {
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

        TdMessageRepository.start(applicationContext)
        TdMessageRepository.addListener(messageListener)
        TdMessageRepository.openChat(chatId)
    }

    override fun onDestroy() {
        TdMessageRepository.removeListener(messageListener)
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

    private fun isNearBottom(): Boolean {
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        return lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 3
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_CHAT_TITLE = "chat_title"

        fun createIntent(context: Context, chatId: Long, chatTitle: String): Intent {
            return Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_CHAT_TITLE, chatTitle)
        }
    }
}
