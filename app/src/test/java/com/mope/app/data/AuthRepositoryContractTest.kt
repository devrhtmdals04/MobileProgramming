package com.mope.app.data

import com.mope.app.data.fake.*
import com.mope.app.data.repository.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthRepositoryContractTest {
    private suspend fun verify(repo: FakeAuthRepository, phone: String = "01012345678") {
        val id = (repo.requestPhoneCode(phone, null).first() as PhoneCodeEvent.CodeSent).verificationId
        repo.verifyPhoneCode(id, "123456")
    }
    @Test fun restorationWaitsAndUnfinishedSignupResumesNameEntry() = runTest {
        val repo = FakeAuthRepository()
        val first = async { repo.session.first() }
        runCurrent()
        assertFalse(first.isCompleted)
        repo.completeRestoration()
        assertEquals(AuthSession.SignedOut, first.await())
        verify(repo)
        assertEquals(AuthSession.ProfileRequired, repo.session.first())
        assertNull(repo.currentUser.first())
        val restored = FakeAuthRepository(repo.store)
        restored.completeRestoration()
        assertEquals(AuthSession.ProfileRequired, restored.session.first())
    }
    @Test fun repeatedPhoneSignInPreservesUidNameAndRole() = runTest {
        val repo = FakeAuthRepository()
        verify(repo)
        repo.completeProfile(" 승민 ")
        val original = repo.currentUser.first()!!
        repo.store.profiles[original.id] = original.copy(role = com.mope.app.data.model.UserRole.CLUB_ADMIN)
        repo.signOut()
        assertNull(repo.currentUser.first())
        verify(repo, "+821012345678")
        assertEquals(original.id, repo.currentUser.first()!!.id)
        repo.completeProfile("덮어쓰지 않음")
        assertEquals("승민", repo.currentUser.first()!!.displayName)
        assertEquals(com.mope.app.data.model.UserRole.CLUB_ADMIN, repo.currentUser.first()!!.role)
        assertEquals(1, repo.store.accounts.size)
    }
    @Test fun wrongAndConsumedCodesDoNotSignIn() = runTest {
        val repo = FakeAuthRepository(); repo.completeRestoration()
        val id = (repo.requestPhoneCode("01012345678", null).first() as PhoneCodeEvent.CodeSent).verificationId
        try { repo.verifyPhoneCode(id, "654321"); fail() } catch (failure: AuthFailure) { assertEquals(AuthFailureReason.INVALID_CREDENTIALS, failure.reason) }
        assertNull(repo.currentUser.first())
        repo.verifyPhoneCode(id, "123456")
        repo.completeProfile("승민"); repo.signOut()
        try { repo.verifyPhoneCode(id, "123456"); fail() } catch (failure: AuthFailure) { assertEquals(AuthFailureReason.CODE_EXPIRED, failure.reason) }
        assertNull(repo.currentUser.first())
    }
    @Test fun failedProfileWriteStaysPendingAndCanBeRetried() = runTest {
        val repo = FakeAuthRepository(); verify(repo)
        repo.nextProfileFailure = AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE)
        try { repo.completeProfile("승민"); fail() } catch (_: AuthFailure) { }
        assertNull(repo.currentUser.first())
        assertEquals(AuthSession.ProfileRequired, repo.session.first())
        repo.completeProfile("승민")
        assertEquals("승민", repo.currentUser.first()!!.displayName)
    }
}
