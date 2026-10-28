package com.mope.app.data

import com.mope.app.data.mock.MockClubRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MockClubRepositoryTest {
    @Test fun everyPromotionReferencesAnExistingClub() = runTest {
        val repository = MockClubRepository()
        val clubs = repository.observeClubs().first()
        val posts = repository.observePromotions().first()
        assertTrue(clubs.isNotEmpty())
        assertTrue(posts.isNotEmpty())
        assertEquals(clubs.size, clubs.map { it.id }.toSet().size)
        assertTrue(posts.all { post -> clubs.any { it.id == post.clubId } })
    }
}
