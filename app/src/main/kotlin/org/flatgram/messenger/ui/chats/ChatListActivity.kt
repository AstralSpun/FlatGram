package org.flatgram.messenger.ui.chats

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import org.drinkless.tdlib.TdApi
import org.flatgram.messenger.adapter.ChatListAdapter
import org.flatgram.messenger.databinding.ActivityChatListBinding
import org.flatgram.messenger.td.ChatListItem
import org.flatgram.messenger.td.TdAuthClient
import org.flatgram.messenger.td.TdChatRepository
import org.flatgram.messenger.td.TdMessageRepository
import org.flatgram.messenger.ui.chat.ChatActivity
import org.flatgram.messenger.ui.login.LoginActivity

class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private lateinit var adapter: ChatListAdapter
    private var chatsStarted = false
    private var openingLogin = false
    private var lastChats: List<ChatListItem> = emptyList()

    private val authListener = object : TdAuthClient.Listener {
        override fun onAuthorizationState(state: TdApi.AuthorizationState) {
            when (ChatListAuthRouter.routeFor(state)) {
                ChatListAuthRoute.ShowChats -> startChats()
                ChatListAuthRoute.OpenLogin -> openLogin()
                ChatListAuthRoute.Wait -> showLoading()
            }
        }

        override fun onTdError(error: TdApi.Error) {
            binding.progress.isVisible = false
            binding.emptyText.isVisible = true
            binding.emptyText.text = "${error.code}: ${error.message}"
        }
    }

    private val chatListener = object : TdChatRepository.Listener {
        override fun onChatsChanged(chats: List<ChatListItem>) {
            if (chats == lastChats) return
            lastChats = chats
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

        adapter = ChatListAdapter(
            onChatClick = { chat ->
                startActivity(ChatActivity.createIntent(this, chat.id, chat.title))
            },
            onAvatarVisible = { chat ->
                TdChatRepository.requestAvatar(chat.id)
            }
        )

        binding.chatRecycler.layoutManager = LinearLayoutManager(this)
        binding.chatRecycler.adapter = adapter

        TdAuthClient.init(applicationContext)
        TdMessageRepository.start(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        openingLogin = false
        TdChatRepository.addListener(chatListener)
        TdAuthClient.addListener(authListener)
    }

    override fun onStop() {
        TdAuthClient.removeListener(authListener)
        TdChatRepository.removeListener(chatListener)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun startChats() {
        if (chatsStarted) return
        chatsStarted = true
        openingLogin = false
        TdChatRepository.start(applicationContext, authorized = true)
    }

    private fun openLogin() {
        if (openingLogin) return
        openingLogin = true
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun showLoading() {
        if (adapter.itemCount > 0) return
        binding.progress.isVisible = true
        binding.emptyText.isVisible = true
        binding.emptyText.text = "Loading chats"
    }
}
