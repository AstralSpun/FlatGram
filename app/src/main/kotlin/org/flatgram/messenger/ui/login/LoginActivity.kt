package org.flatgram.messenger.ui.login

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.drinkless.tdlib.TdApi
import org.flatgram.messenger.databinding.ActivityLoginBinding
import org.flatgram.messenger.td.TdAuthClient
import org.flatgram.messenger.ui.chats.ChatListActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var currentState: TdApi.AuthorizationState? = null
    private var openedChatList = false

    private val tdListener = object : TdAuthClient.Listener {
        override fun onAuthorizationState(state: TdApi.AuthorizationState) {
            currentState = state
            render(state)
        }

        override fun onTdError(error: TdApi.Error) {
            setLoading(false)
            binding.subtitle.text = "${error.code}: ${error.message}"
            binding.inputLayout.isVisible = true
            binding.submitButton.isVisible = true
            binding.inputLayout.error = error.message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.submitButton.setOnClickListener {
            submit()
        }

        TdAuthClient.init(applicationContext)
        TdAuthClient.addListener(tdListener)
    }

    override fun onDestroy() {
        TdAuthClient.removeListener(tdListener)
        super.onDestroy()
    }

    private fun submit() {
        val value = binding.inputEdit.text?.toString()?.trim().orEmpty()
        binding.inputLayout.error = null

        when (currentState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                if (!value.startsWith("+")) {
                    binding.inputLayout.error = "Use international format, for example +8613800000000"
                    return
                }
                setLoading(true)
                TdAuthClient.setPhoneNumber(value)
            }

            is TdApi.AuthorizationStateWaitCode -> {
                if (value.isEmpty()) return
                setLoading(true)
                TdAuthClient.checkCode(value)
            }

            is TdApi.AuthorizationStateWaitEmailAddress -> {
                if (value.isEmpty()) return
                setLoading(true)
                TdAuthClient.setEmailAddress(value)
            }

            is TdApi.AuthorizationStateWaitEmailCode -> {
                if (value.isEmpty()) return
                setLoading(true)
                TdAuthClient.checkEmailCode(value)
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                if (value.isEmpty()) return
                setLoading(true)
                TdAuthClient.checkPassword(value)
            }
        }
    }
    private fun render(state: TdApi.AuthorizationState) {
        setLoading(false)
        binding.inputLayout.error = null

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                showLoading("Connecting", "Preparing Telegram login.")
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                showInput(
                    title = "Your phone",
                    subtitle = "Enter your Telegram phone number.",
                    hint = "Phone number",
                    inputType = InputType.TYPE_CLASS_PHONE
                )
            }

            is TdApi.AuthorizationStateWaitCode -> {
                showInput(
                    title = "Code",
                    subtitle = "Code sent to ${state.codeInfo.phoneNumber}.",
                    hint = "Login code",
                    inputType = InputType.TYPE_CLASS_NUMBER
                )
            }

            is TdApi.AuthorizationStateWaitEmailAddress -> {
                showInput(
                    title = "Email",
                    subtitle = "Telegram requires an email address for this login.",
                    hint = "Email address",
                    inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                )
            }

            is TdApi.AuthorizationStateWaitEmailCode -> {
                showInput(
                    title = "Email code",
                    subtitle = "Code sent to ${state.codeInfo.emailAddressPattern}.",
                    hint = "Email code",
                    inputType = InputType.TYPE_CLASS_NUMBER
                )
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                val hint = state.passwordHint.takeIf { it.isNotBlank() }
                showInput(
                    title = "Two-step verification",
                    subtitle = hint?.let { "Password hint: $it" } ?: "Enter your Telegram password.",
                    hint = "Password",
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
            }

            is TdApi.AuthorizationStateReady -> {
                openChatList()
                binding.title.text = "Signed in"
                binding.subtitle.text = "TDLib is ready."
                binding.inputLayout.isVisible = false
                binding.submitButton.isVisible = false
            }

            is TdApi.AuthorizationStateWaitRegistration -> {
                showBlocked("Registration required", "New account registration is not implemented yet.")
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                showBlocked("Confirm login", state.link)
            }

            else -> {
                showLoading("Connecting", "Waiting for Telegram.")
            }
        }
    }

    private fun showInput(
        title: String,
        subtitle: String,
        hint: String,
        inputType: Int
    ) {
        binding.title.text = title
        binding.subtitle.text = subtitle
        binding.inputLayout.hint = hint
        binding.inputLayout.isVisible = true
        binding.inputEdit.text = null
        binding.inputEdit.inputType = inputType
        binding.submitButton.isVisible = true
        binding.submitButton.isEnabled = true
    }

    private fun showLoading(title: String, subtitle: String) {
        binding.title.text = title
        binding.subtitle.text = subtitle
        binding.inputLayout.isVisible = false
        binding.submitButton.isVisible = false
        binding.progress.isVisible = true
    }

    private fun showBlocked(title: String, subtitle: String) {
        binding.title.text = title
        binding.subtitle.text = subtitle
        binding.inputLayout.isVisible = false
        binding.submitButton.isVisible = false
        binding.progress.isVisible = false
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.submitButton.isEnabled = !loading
    }

    private fun openChatList() {
        if (openedChatList) return
        openedChatList = true
        startActivity(Intent(this, ChatListActivity::class.java))
        finish()
    }
}
