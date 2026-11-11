package com.mope.app.domain

import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import org.junit.Assert.*
import org.junit.Test

class AuthInputTest {
    @Test fun passwordResetValidatesEmailWithoutRequiringPassword() {
        assertEquals("Student@example.com", AuthInput.passwordReset("  Student@example.com  "))
        listOf("", "a@@b.com", "a@b", "a b@c.com").forEach { invalid { AuthInput.passwordReset(it) } }
    }
    @Test fun normalizesEmailAndNameButPreservesPassword() {
        val input = AuthInput.signUp("  Student@example.com  ", " secret ", "  승민  ")
        assertEquals("Student@example.com", input.email)
        assertEquals(" secret ", input.password)
        assertEquals("승민", input.displayName)
    }

    @Test fun rejectsMalformedEmails() {
        listOf("", "student", "a@@b.com", "a@b", "a b@c.com").forEach {
            invalid { AuthInput.signIn(it, "secret") }
        }
    }

    @Test fun signInDoesNotApplyNewAccountPasswordLengthPolicy() {
        assertEquals("x", AuthInput.signIn("a@b.com", "x").password)
        invalid { AuthInput.signIn("a@b.com", "") }
    }

    @Test fun signUpValidatesPasswordAndTrimmedNameBoundaries() {
        listOf("", "12345", "      ").forEach {
            invalid { AuthInput.signUp("a@b.com", it, "승민") }
        }
        listOf("", "   ", "가".repeat(31)).forEach {
            invalid { AuthInput.signUp("a@b.com", "123456", it) }
        }
        assertEquals(30, AuthInput.signUp("a@b.com", "123456", "가".repeat(30)).displayName.length)
    }

    private fun invalid(block: () -> Unit) {
        val failure = assertThrows(AuthFailure::class.java) { block() }
        assertEquals(AuthFailureReason.INVALID_INPUT, failure.reason)
    }
}
