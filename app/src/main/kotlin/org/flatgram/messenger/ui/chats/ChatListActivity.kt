package org.flatgram.messenger.ui.chats

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import org.flatgram.messenger.adapter.ChatListAdapter
import org.flatgram.messenger.databinding.ActivityChatListBinding
import org.flatgram.messenger.td.ChatListItem
import org.flatgram.messenger.td.TdChatRepository

class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private lateinit var adapter: ChatListAdapter

    private val chatListener = object : TdChatRepository.Listener {
        override fun onChatsChanged(chats: List<ChatListItem>) {
            adapter.submitList(chats)
            binding.progress.isVisible = false
            binding.emptyText.isVisible = chats.isEmpty()
            binding.emptyText.text = if (chats.isEmpty()) "No chats" else ""
        }

        override fun onChatError(message: String) {
            binding.progress.isVisible = false
            binding.emptyText.isVisible = true
            binding.emptyText.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChatListAdapter { chat ->
            Toast.makeText(this, chat.title, Toast.LENGTH_SHORT).show()
        }

        binding.chatRecycler.layoutManager = LinearLayoutManager(this)
        binding.chatRecycler.adapter = adapter

        TdChatRepository.start(applicationContext)
        TdChatRepository.addListener(chatListener)
    }

    override fun onDestroy() {
        TdChatRepository.removeListener(chatListener)
        super.onDestroy()
    }
}
