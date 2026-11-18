package com.mope.app.domain

import com.mope.app.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class AuthInputTest {
    @Test fun normalizesKoreanMobileAndInternationalNumbers() {
        assertEquals("+821012345678", AuthInput.phoneNumber(" 010-1234-5678 "))
        assertEquals("+821012345678", AuthInput.phoneNumber("+82 10 1234 5678"))
        assertEquals("+16505550123", AuthInput.phoneNumber("+1 (650) 555-0123"))
    }
    @Test fun rejectsAmbiguousOrMalformedNumbers() {
        listOf("", "010123", "010123456789", "821012345678", "+0123456789", "+1234567890123456", "010a12345678", "010\n12345678").forEach { invalid { AuthInput.phoneNumber(it) } }
    }
    @Test fun requiresExactlySixAsciiDigits() {
        assertEquals("012345", AuthInput.verificationCode("012345"))
        listOf("", "12345", "1234567", "12a456", " 123456", "１２３４５６").forEach { invalid { AuthInput.verificationCode(it) } }
    }
    @Test fun validatesTrimmedNameBoundaries() {
        assertEquals("승민", AuthInput.displayName(" 승민 "))
        assertEquals(30, AuthInput.displayName("가".repeat(30)).length)
        listOf("", "   ", "가".repeat(31)).forEach { invalid { AuthInput.displayName(it) } }
    }
    private fun invalid(block: () -> Unit) {
        assertEquals(AuthFailureReason.INVALID_INPUT, assertThrows(AuthFailure::class.java) { block() }.reason)
    }
}
