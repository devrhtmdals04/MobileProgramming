package com.mope.app.ui

import com.mope.app.data.fake.FakeAuthRepository
import com.mope.app.data.repository.AuthFailure
import com.mope.app.data.repository.AuthFailureReason
import com.mope.app.ui.screens.auth.AuthFormMode
import com.mope.app.ui.screens.auth.AuthMessages
import com.mope.app.ui.screens.auth.AuthSessionUiState
import com.mope.app.ui.screens.auth.AuthViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun startsRestoringUntilRepositoryEmits() = runTest {
        val repository = FakeAuthRepository()
        val vm = AuthViewModel(repository)
        assertEquals(AuthSessionUiState.Restoring, vm.session.value)
        repository.completeRestoration()
        runCurrent()
        assertEquals(AuthSessionUiState.SignedOut, vm.session.value)
    }

    @Test
    fun signInSuccessClearsPasswordAndPublishesUser() = runTest {
        val repository = FakeAuthRepository()
        repository.completeRestoration()
        repository.signUp("student@example.com", "secret1", "민성")
        repository.signOut()
        val vm = AuthViewModel(repository)
        repository.completeRestoration()
        runCurrent()

        vm.onEmailChange("student@example.com")
        vm.onPasswordChange("secret1")
        vm.submit()
        advanceUntilIdle()

        assertEquals("", vm.form.value.password)
        assertFalse(vm.form.value.isSubmitting)
        val session = vm.session.value as AuthSessionUiState.SignedIn
        assertEquals("민성", session.user.displayName)
    }

    @Test
    fun invalidInputShowsMessageAndAllowsRetry() = runTest {
        val repository = FakeAuthRepository()
        val vm = AuthViewModel(repository)
        repository.completeRestoration()
        runCurrent()

        vm.onEmailChange("not-an-email")
        vm.onPasswordChange("secret1")
        vm.submit()
        advanceUntilIdle()

        assertEquals(AuthMessages.from(AuthFailureReason.INVALID_INPUT), vm.form.value.errorMessage)
        assertFalse(vm.form.value.isSubmitting)

        vm.onEmailChange("student@example.com")
        assertEquals(null, vm.form.value.errorMessage)
    }

    @Test
    fun duplicateSubmitWhileProcessingIsIgnored() = runTest {
        var signInCalls = 0
        val fake = FakeAuthRepository()
        fake.completeRestoration()
        fake.signUp("student@example.com", "secret1", "민성")
        fake.signOut()
        val repository = object : com.mope.app.data.repository.AuthRepository {
            override val currentUser = fake.currentUser
            override suspend fun signIn(email: String, password: String) {
                signInCalls += 1
                kotlinx.coroutines.yield()
                fake.signIn(email, password)
            }
            override suspend fun signUp(email: String, password: String, displayName: String) =
                fake.signUp(email, password, displayName)
            override suspend fun signOut() = fake.signOut()
        }
        val vm = AuthViewModel(repository)
        fake.completeRestoration()
        runCurrent()

        vm.onEmailChange("student@example.com")
        vm.onPasswordChange("secret1")
        vm.submit()
        assertTrue(vm.form.value.isSubmitting)
        vm.submit()
        advanceUntilIdle()

        assertEquals(1, signInCalls)
    }

    @Test
    fun existingAccountFailureKeepsSignUpMode() = runTest {
        val repository = FakeAuthRepository()
        repository.completeRestoration()
        repository.signUp("student@example.com", "secret1", "민성")
        repository.signOut()
        val vm = AuthViewModel(repository)
        repository.completeRestoration()
        runCurrent()

        vm.setMode(AuthFormMode.SignUp)
        vm.onEmailChange("student@example.com")
        vm.onPasswordChange("secret1")
        vm.onDisplayNameChange("다른이름")
        vm.submit()
        advanceUntilIdle()

        assertEquals(AuthFormMode.SignUp, vm.form.value.mode)
        assertEquals(
            AuthMessages.from(AuthFailureReason.ACCOUNT_ALREADY_EXISTS),
            vm.form.value.errorMessage,
        )
    }

    @Test
    fun profileUnavailableUsesRetryableSessionState() = runTest {
        val repository = FakeAuthRepository()
        repository.nextProfileFailure = AuthFailure(AuthFailureReason.PROFILE_UNAVAILABLE)
        val vm = AuthViewModel(repository)
        runCurrent()
        try {
            repository.signUp("student@example.com", "secret1", "민성")
        } catch (_: AuthFailure) {
        }
        advanceUntilIdle()

        val unavailable = vm.session.value as AuthSessionUiState.Unavailable
        assertEquals(AuthMessages.from(AuthFailureReason.PROFILE_UNAVAILABLE), unavailable.message)

        repository.signOut()
        vm.retrySession()
        advanceUntilIdle()
        assertEquals(AuthSessionUiState.SignedOut, vm.session.value)
    }

    @Test
    fun signOutReturnsToSignedOut() = runTest {
        val repository = FakeAuthRepository()
        repository.completeRestoration()
        repository.signUp("student@example.com", "secret1", "민성")
        val vm = AuthViewModel(repository)
        runCurrent()
        assertTrue(vm.session.value is AuthSessionUiState.SignedIn)

        vm.signOut()
        advanceUntilIdle()
        assertEquals(AuthSessionUiState.SignedOut, vm.session.value)
    }
}
