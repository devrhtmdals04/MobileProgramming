package com.mope.app.domain

import com.mope.app.data.mock.MockData
import org.junit.Assert.*
import org.junit.Test

class FilterClubsTest {
    @Test fun blankQueryReturnsAllClubs() {
        assertEquals(MockData.clubs, filterClubs(MockData.clubs, "  ", null))
    }
    @Test fun searchesDescriptionAndTrimsWhitespace() {
        assertEquals(listOf("code"), filterClubs(MockData.clubs, " 프로젝트 ", null).map { it.id })
    }
    @Test fun combinesCategoryAndName() {
        assertTrue(filterClubs(MockData.clubs, "사진", "운동").isEmpty())
        assertEquals(listOf("photo"), filterClubs(MockData.clubs, "사진", "문화").map { it.id })
    }
    @Test fun matchesLatinLettersIgnoringCase() {
        val clubs = listOf(MockData.clubs.first().copy(name = "Photo Club"))
        assertEquals(clubs, filterClubs(clubs, "PHOTO", null))
    }
}
