package com.mope.app.domain

import com.mope.app.data.repository.RequestFailure
import org.junit.Assert.*
import org.junit.Test

class ClubAdminInputTest {
    @Test fun canonicalDirectoryIdentityRejectsAlternatePaths() {
        assertEquals("https://clubunion.ssu.ac.kr/clubs/63", ClubAdminInput.sourceUrl("ssu-63"))
        listOf("ssu-0", "ssu-01", "ssu-1/../../operators", "photo", "ssu-1234567890").forEach { invalid { ClubAdminInput.sourceUrl(it) } }
    }
    @Test fun verificationChannelMustBeHttpsWithoutEmbeddedCredentials() {
        assertEquals("https://example.com/club", ClubAdminInput.channel(" https://example.com/club "))
        listOf("http://example.com", "https://", "javascript:alert(1)", "https://user:secret@example.com", "https://example.com/a b").forEach { invalid { ClubAdminInput.channel(it) } }
    }
    @Test fun multilineReasonIsTrimmedAndBounded() {
        assertEquals("대표자\n확인 요청", ClubAdminInput.reason(" 대표자\n확인 요청 "))
        invalid { ClubAdminInput.reason(" \n ") }
        invalid { ClubAdminInput.reason("가".repeat(1001)) }
        invalid { ClubAdminInput.description("가".repeat(2001)) }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid input") } catch (_: RequestFailure) { }
    }
}
