package com.mope.app.data.repository

enum class ChatFailureReason {
    INVALID_INPUT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND,
    SELF_CHAT, CONFLICT, NETWORK, INVALID_DATA, UNKNOWN,
}

class ChatFailure(val reason: ChatFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)
