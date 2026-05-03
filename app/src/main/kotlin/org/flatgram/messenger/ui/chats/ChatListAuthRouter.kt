package org.flatgram.messenger.ui.chats

import org.drinkless.tdlib.TdApi

enum class ChatListAuthRoute {
    Wait,
    ShowChats,
    OpenLogin
}

object ChatListAuthRouter {
    fun routeFor(state: TdApi.AuthorizationState): ChatListAuthRoute {
        return when (state) {
            is TdApi.AuthorizationStateReady -> ChatListAuthRoute.ShowChats
            is TdApi.AuthorizationStateWaitPhoneNumber,
            is TdApi.AuthorizationStateWaitCode,
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode,
            is TdApi.AuthorizationStateWaitPassword,
            is TdApi.AuthorizationStateWaitRegistration,
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> ChatListAuthRoute.OpenLogin
            else -> ChatListAuthRoute.Wait
        }
    }
}
