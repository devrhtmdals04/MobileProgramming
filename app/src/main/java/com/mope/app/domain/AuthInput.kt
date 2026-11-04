package com.mope.app.domain

import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason

/** 비밀번호는 공백을 포함한 원문을 보존한다. 이메일의 대소문자도 변경하지 않는다. */
data class SignInInput(val email: String, val password: String)
data class SignUpInput(val email: String, val password: String, val displayName: String)

object AuthInput {
    fun signIn(email: String, password: String): SignInInput {
        val normalizedEmail = email.trim()
        requireInput(normalizedEmail.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
        // 기존 계정의 로그인에는 신규 가입 비밀번호 정책을 강제하지 않는다.
        requireInput(password.isNotEmpty())
        return SignInInput(normalizedEmail, password)
    }

    fun signUp(email: String, password: String, displayName: String): SignUpInput {
        val credentials = signIn(email, password)
        val name = displayName.trim()
        requireInput(password.length >= 6 && password.isNotBlank())
        requireInput(name.length in 1..30)
        return SignUpInput(credentials.email, credentials.password, name)
    }

    private fun requireInput(valid: Boolean) {
        if (!valid) throw AuthFailure(AuthFailureReason.INVALID_INPUT)
    }
}
