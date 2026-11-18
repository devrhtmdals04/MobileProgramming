package com.mope.app.domain

import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason

object AuthInput {
    /** Korean mobile numbers may be entered locally; other countries use E.164. */
    fun phoneNumber(value: String): String {
        requireInput(value.trim().matches(Regex("[+0-9() -]+")))
        val compact = value.trim().replace(Regex("[() -]"), "")
        val normalized = if (compact.startsWith("010") && compact.length == 11) {
            "+82" + compact.drop(1)
        } else compact
        requireInput(normalized.matches(Regex("\\+[1-9][0-9]{7,14}")))
        return normalized
    }

    fun verificationCode(value: String): String {
        requireInput(value.matches(Regex("[0-9]{6}")))
        return value
    }

    fun displayName(value: String): String = value.trim().also {
        requireInput(it.length in 1..30)
    }

    private fun requireInput(valid: Boolean) {
        if (!valid) throw AuthFailure(AuthFailureReason.INVALID_INPUT)
    }
}
