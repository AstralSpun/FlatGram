package org.flatgram.messenger.td

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.flatgram.messenger.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object TdAuthClient {

    interface Listener {
        fun onAuthorizationState(state: TdApi.AuthorizationState)
        fun onTdError(error: TdApi.Error)
    }

    interface UpdateListener {
        fun onTdUpdate(update: TdApi.Update)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val updateListeners = CopyOnWriteArraySet<UpdateListener>()

    private var client: Client? = null

    private var appContext: Context? = null

    private val tdlibParametersRequestSent = AtomicBoolean(false)

    fun init(context: Context) {
        appContext = context.applicationContext

        if (client != null) return
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (t: Throwable) {
            Log.d("TdAuthClient: ", "init", t)
        }
        client = Client.create(
            { update -> handleUpdate(update) },
            null,
            null
        )
        refreshAuthorizationState()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        refreshAuthorizationState()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun addUpdateListener(listener: UpdateListener) {
        updateListeners.add(listener)
    }

    fun removeUpdateListener(listener: UpdateListener) {
        updateListeners.remove(listener)
    }

    fun setPhoneNumber(phoneNumber: String) {
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            allowMissedCall = false
            isCurrentPhoneNumber = false
            hasUnknownPhoneNumber = false
            allowSmsRetrieverApi = false
        }
        send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings))
    }

    fun checkCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    fun setEmailAddress(email: String) {
        send(TdApi.SetAuthenticationEmailAddress(email))
    }

    fun checkEmailCode(code: String) {
        send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }

    fun checkPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun resendCode() {
        send(TdApi.ResendAuthenticationCode())
    }

    private fun handleUpdate(update: TdApi.Object) {
        Log.d("TdAuthClient", "update: ${update.javaClass.simpleName}")
        if (update is TdApi.UpdateAuthorizationState) {
            handleAuthorizationState(update.authorizationState)
        }
        if (update is TdApi.Update) {
            updateListeners.forEach { it.onTdUpdate(update) }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        Log.d("TdAuthClient", "authorization state: ${state.javaClass.simpleName}")
        if (state is TdApi.AuthorizationStateWaitTdlibParameters) {
            setTdlibParameters()
        }
        mainHandler.post {
            listeners.forEach { it.onAuthorizationState(state) }
        }
    }

    private fun setTdlibParameters() {
        val context = appContext ?: return
        if (!tdlibParametersRequestSent.compareAndSet(false, true)) return

        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.filesDir, "td-db").absolutePath
            filesDirectory = File(context.filesDir, "td-files").absolutePath
            databaseEncryptionKey = byteArrayOf()
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = true
            apiId = BuildConfig.TELEGRAM_API_ID
            apiHash = BuildConfig.TELEGRAM_API_HASH
            systemLanguageCode = Locale.getDefault().toLanguageTag()
            deviceModel = Build.MODEL
            systemVersion = Build.VERSION.RELEASE
            applicationVersion = BuildConfig.VERSION_NAME
        }
        send(parameters, emitErrors = false) { result ->
            when (result) {
                is TdApi.Error -> {
                    val isDuplicateRequest = result.code == 400 &&
                            result.message.contains("Unexpected setTdlibParameters", ignoreCase = true)
                    if (isDuplicateRequest) {
                        Log.d("TdAuthClient", "ignored duplicate SetTdlibParameters")
                        refreshAuthorizationState()
                    } else {
                        tdlibParametersRequestSent.set(false)
                        emitError(result)
                    }
                }

                else -> refreshAuthorizationState()
            }
        }
    }

    private fun refreshAuthorizationState() {
        send(TdApi.GetAuthorizationState()) { result ->
            if (result is TdApi.AuthorizationState) {
                handleAuthorizationState(result)
            }
        }
    }

    fun send(
        function: TdApi.Function<*>,
        emitErrors: Boolean = true,
        onResult: (TdApi.Object) -> Unit = {}
    ) {
        val tdClient = client ?: return

        tdClient.send(function) { result ->
            if (result is TdApi.Error && emitErrors) {
                Log.e("TdAuthClient", "${function.javaClass.simpleName} failed: ${result.code} ${result.message}")
                emitError(result)
            }
            onResult(result)
        }
    }

    private fun emitError(error: TdApi.Error) {
        mainHandler.post {
            listeners.forEach { it.onTdError(error) }
        }
    }
}
