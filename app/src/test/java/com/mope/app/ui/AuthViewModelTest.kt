package com.mope.app.ui

import com.mope.app.data.fake.FakeAuthRepository
import com.mope.app.data.repository.*
import com.mope.app.ui.screens.auth.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun startsRestoringThenSignedOut() = runTest {
        val repo = FakeAuthRepository(); val vm = AuthViewModel(repo)
        assertEquals(AuthSessionUiState.Restoring, vm.session.value)
        repo.completeRestoration(); runCurrent()
        assertEquals(AuthSessionUiState.SignedOut, vm.session.value)
    }
    @Test fun firstSignupRequiresNameThenReturningLoginSkipsIt() = runTest {
        val repo = FakeAuthRepository(); repo.completeRestoration(); val vm = AuthViewModel(repo); runCurrent()
        vm.onPhoneNumberChange("01012345678"); vm.requestCode(null); advanceUntilIdle()
        assertEquals(AuthStep.Code, vm.form.value.step)
        vm.onCodeChange("123456"); vm.submit(); advanceUntilIdle()
        assertEquals(AuthStep.Profile, vm.form.value.step)
        assertEquals("", vm.form.value.code)
        assertEquals(AuthSessionUiState.SignedOut, vm.session.value)
        vm.onDisplayNameChange("승민"); vm.submit(); advanceUntilIdle()
        assertEquals("승민", (vm.session.value as AuthSessionUiState.SignedIn).user.displayName)
        vm.signOut(); advanceUntilIdle()
        vm.onPhoneNumberChange("01012345678"); vm.requestCode(null); advanceUntilIdle()
        vm.onCodeChange("123456"); vm.submit(); advanceUntilIdle()
        assertTrue(vm.session.value is AuthSessionUiState.SignedIn)
        assertEquals("", vm.form.value.code)
    }
    @Test fun invalidNumberAndWrongCodeCanBeRetried() = runTest {
        val repo = FakeAuthRepository(); repo.completeRestoration(); val vm = AuthViewModel(repo); runCurrent()
        vm.onPhoneNumberChange("bad"); vm.requestCode(null); advanceUntilIdle()
        assertEquals(0, repo.requests)
        assertFalse(vm.form.value.isSubmitting)
        assertNotNull(vm.form.value.errorMessage)
        vm.onPhoneNumberChange("01012345678"); vm.requestCode(null); advanceUntilIdle()
        vm.onCodeChange("654321"); vm.submit(); advanceUntilIdle()
        assertEquals(AuthStep.Code, vm.form.value.step)
        assertEquals(AuthMessages.from(AuthFailureReason.INVALID_CREDENTIALS), vm.form.value.errorMessage)
        vm.onCodeChange("123456"); vm.submit(); advanceUntilIdle()
        assertEquals(AuthStep.Profile, vm.form.value.step)
    }
    @Test fun duplicateSubmissionsAreIgnoredAndPhoneChangeClearsChallenge() = runTest {
        val repo = FakeAuthRepository(); repo.completeRestoration(); val vm = AuthViewModel(repo); runCurrent()
        vm.onPhoneNumberChange("01012345678"); vm.requestCode(null); vm.requestCode(null); advanceUntilIdle()
        assertEquals(1, repo.requests)
        vm.onCodeChange("123456"); vm.changePhoneNumber()
        assertEquals(AuthStep.Phone, vm.form.value.step)
        assertNull(vm.form.value.verificationId)
        assertEquals("", vm.form.value.code)
        vm.requestCode(null); advanceUntilIdle(); vm.onCodeChange("123456")
        vm.submit(); vm.submit(); advanceUntilIdle()
        assertEquals(1, repo.verifications)
    }
    @Test fun automaticVerificationAllowsNameEntryWhenSessionArrivesBeforeCallback() = runTest {
        val fake = FakeAuthRepository(); fake.completeRestoration()
        val repository = object : AuthRepository by fake {
            override fun requestPhoneCode(phoneNumber: String, activity: android.app.Activity?) = kotlinx.coroutines.flow.flow {
                val sent = fake.requestPhoneCode(phoneNumber, null).first() as PhoneCodeEvent.CodeSent
                fake.verifyPhoneCode(sent.verificationId, "123456")
                kotlinx.coroutines.yield()
                emit(PhoneCodeEvent.AutomaticallyVerified)
            }
        }
        val vm = AuthViewModel(repository); runCurrent()
        vm.onPhoneNumberChange("01012345678"); vm.requestCode(null); advanceUntilIdle()
        assertEquals(AuthStep.Profile, vm.form.value.step)
        assertFalse(vm.form.value.isSubmitting)
        vm.onDisplayNameChange("자동 인증"); vm.submit(); advanceUntilIdle()
        assertTrue(vm.session.value is AuthSessionUiState.SignedIn)
    }

    @Test fun pendingProfileIsRestoredAfterRecreation() = runTest {
        val repo = FakeAuthRepository()
        val id = (repo.requestPhoneCode("01012345678", null).firstEvent()).verificationId
        repo.verifyPhoneCode(id, "123456")
        val vm = AuthViewModel(repo); runCurrent()
        assertEquals(AuthStep.Profile, vm.form.value.step)
        vm.signOut(); advanceUntilIdle()
        assertEquals(AuthStep.Phone, vm.form.value.step)
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<PhoneCodeEvent>.firstEvent() = this.first() as PhoneCodeEvent.CodeSent
