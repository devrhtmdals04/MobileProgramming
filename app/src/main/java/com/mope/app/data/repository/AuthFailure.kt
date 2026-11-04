package com.mope.app.data.repository

/** SDK 예외를 화면에 노출하지 않기 위한 인증 오류 계약. */
enum class AuthFailureReason {
    INVALID_INPUT, INVALID_CREDENTIALS, ACCOUNT_ALREADY_EXISTS,
    NETWORK, PERMISSION_DENIED, PROFILE_UNAVAILABLE, UNKNOWN,
}

class AuthFailure(
    val reason: AuthFailureReason,
    cause: Throwable? = null,
) : Exception(reason.name, cause)
