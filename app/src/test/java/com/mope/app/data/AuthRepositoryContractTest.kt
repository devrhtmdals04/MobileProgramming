package com.mope.app.data

import com.mope.app.data.fake.FakeAuthRepository
import com.mope.app.data.fake.FakeAuthStore
import com.mope.app.data.model.UserRole
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthRepositoryContractTest {
    @Test fun passwordResetDoesNotRevealAccountExistenceOrChangeSession() = runTest {
        val repository = FakeAuthRepository()
        repository.signUp("a@b.com", "secret", "승민")
        val user = repository.currentUser.first()
        repository.requestPasswordReset(" a@b.com ")
        repository.requestPasswordReset("missing@b.com")
        assertEquals(user, repository.currentUser.first())
        repository.signOut()
        repository.requestPasswordReset("a@b.com")
        assertNull(repository.currentUser.first())
        assertEquals(listOf("a@b.com", "missing@b.com", "a@b.com"), repository.passwordResetRequests)
    }

    @Test fun passwordResetValidationFailureAndCancellationAreNotSuccess() = runTest {
        val repository = FakeAuthRepository()
        failure(AuthFailureReason.INVALID_INPUT) { repository.requestPasswordReset("bad") }
        repository.nextPasswordResetFailure = AuthFailure(AuthFailureReason.NETWORK)
        failure(AuthFailureReason.NETWORK) { repository.requestPasswordReset("a@b.com") }
        val cancelled = CancellationException("cancelled")
        repository.nextPasswordResetFailure = cancelled
        try { repository.requestPasswordReset("a@b.com"); fail("Expected cancellation") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertTrue(repository.passwordResetRequests.isEmpty())
    }
    @Test fun restorationDoesNotEmitLoggedOutBeforeItCompletes() = runTest {
        val repository = FakeAuthRepository()
        val first = async { repository.currentUser.first() }
        runCurrent()
        assertFalse(first.isCompleted)
        repository.completeRestoration()
        assertNull(first.await())
    }

    @Test fun signUpStoresStudentProfileAndSignOutClearsSession() = runTest {
        val repository = FakeAuthRepository()
        repository.signUp(" student@example.com ", "secret", " 승민 ")
        val user = repository.currentUser.first()!!
        assertEquals("승민", user.displayName)
        assertEquals(UserRole.STUDENT, user.role)
        assertEquals(user, repository.store.profiles[user.id])
        repository.signOut()
        assertNull(repository.currentUser.first())
        assertNull(repository.store.sessionUid)
    }

    @Test fun duplicateEmailAndWrongPasswordDoNotAuthenticate() = runTest {
        val repository = FakeAuthRepository()
        repository.signUp("student@example.com", "secret", "승민")
        repository.signOut()
        failure(AuthFailureReason.ACCOUNT_ALREADY_EXISTS) {
            repository.signUp("STUDENT@example.com", "secret", "민성")
        }
        failure(AuthFailureReason.INVALID_CREDENTIALS) {
            repository.signIn("student@example.com", "wrong")
        }
        assertNull(repository.currentUser.first())
        assertEquals(1, repository.store.accounts.size)
    }

    @Test fun newRepositoryRestoresExistingSessionAndPreservesProfile() = runTest {
        val store = FakeAuthStore()
        val first = FakeAuthRepository(store)
        first.signUp("a@b.com", "secret", "승민")
        val original = first.currentUser.first()!!
        store.profiles[original.id] = original.copy(role = UserRole.CLUB_ADMIN)
        val restored = FakeAuthRepository(store)
        restored.completeRestoration()
        assertEquals(UserRole.CLUB_ADMIN, restored.currentUser.first()!!.role)
        restored.signOut()
        restored.signIn("a@b.com", "secret")
        assertEquals(UserRole.CLUB_ADMIN, restored.currentUser.first()!!.role)
    }

    @Test fun profileFailureIsNotSuccessAndSignInRepairsMissingProfile() = runTest {
        val repository = FakeAuthRepository()
        repository.nextProfileFailure = IllegalStateException("offline")
        failure(AuthFailureReason.PROFILE_UNAVAILABLE) {
            repository.signUp("a@b.com", "secret", "승민")
        }
        assertTrue(repository.store.profiles.isEmpty())
        failure(AuthFailureReason.PROFILE_UNAVAILABLE) { repository.currentUser.first() }
        repository.signIn("a@b.com", "secret")
        assertEquals("승민", repository.currentUser.first()!!.displayName)
        assertEquals(1, repository.store.accounts.size)
    }

    @Test fun missingProfileDuringRestorationIsAnErrorRatherThanLogout() = runTest {
        val repository = FakeAuthRepository()
        repository.store.sessionUid = "missing"
        repository.completeRestoration()
        failure(AuthFailureReason.PROFILE_UNAVAILABLE) { repository.currentUser.first() }
        repository.signOut()
        assertNull(repository.currentUser.first())
    }

    @Test fun invalidInputDoesNotCreateAccountAndCancellationIsPreserved() = runTest {
        val repository = FakeAuthRepository()
        failure(AuthFailureReason.INVALID_INPUT) { repository.signUp("bad", "secret", "승민") }
        assertTrue(repository.store.accounts.isEmpty())
        val cancellation = CancellationException("cancelled")
        repository.nextProfileFailure = cancellation
        try {
            repository.signUp("a@b.com", "secret", "승민")
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        repository.signIn("a@b.com", "secret")
        assertNotNull(repository.currentUser.first())
    }

    private suspend fun failure(reason: AuthFailureReason, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $reason")
        } catch (actual: AuthFailure) {
            assertEquals(reason, actual.reason)
        }
    }
}
