package com.mope.app.ui.request

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.mope.app.data.model.*
import com.mope.app.data.repository.*
import com.mope.app.ui.MainDispatcherRule
import com.mope.app.ui.components.LoadState
import com.mope.app.ui.screens.request.ClubRequestViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClubRequestViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun validatesFormAndIgnoresDuplicateSubmission() = runTest {
        val auth = TestAuth(); val repo = TestRequests(); val vm = ClubRequestViewModel(auth, repo, SavedStateHandle())
        runCurrent()
        vm.submit(); assertEquals(0, repo.calls.size)
        vm.onNameChange("새 동아리"); vm.onPurposeChange("활동 계획")
        val gate = CompletableDeferred<Unit>(); repo.beforeSubmit = { gate.await() }
        vm.submit(); vm.submit(); runCurrent()
        assertEquals(1, repo.calls.size)
        assertTrue(vm.state.value.isSubmitting)
        vm.onNameChange("변경 금지"); assertEquals("새 동아리", vm.state.value.name)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.submitted)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.purpose)
        val requests = (vm.state.value.history as LoadState.Content).value
        assertEquals(listOf("새 동아리"), requests.map { it.name })
    }
    @Test fun uncertainNetworkRetryKeepsIdAcrossRecreation() = runTest {
        val auth = TestAuth(); val repo = TestRequests(); val saved = SavedStateHandle()
        val vm = ClubRequestViewModel(auth, repo, saved); val owner = ViewModelStore().apply { put("vm", vm) }
        runCurrent(); vm.onNameChange(" 같은 요청 "); vm.onPurposeChange(" 계획 ")
        repo.afterCommit = { throw RequestFailure(RequestFailureReason.NETWORK) }
        vm.submit(); advanceUntilIdle()
        assertFalse(vm.state.value.submitted)
        assertNotNull(vm.state.value.error)
        assertEquals(1, repo.history.value.size)
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        owner.clear()
        val next = ClubRequestViewModel(auth, repo, restored); runCurrent()
        repo.afterCommit = {}; next.submit(); advanceUntilIdle()
        assertEquals(2, repo.calls.size)
        assertEquals(1, repo.calls.distinct().size)
        assertEquals(1, repo.history.value.size)
        assertTrue(next.state.value.submitted)
    }
    @Test fun logoutDiscardsDraftAndHistoryAndBlocksSubmit() = runTest {
        val auth = TestAuth(); val repo = TestRequests(); val saved = SavedStateHandle()
        val vm = ClubRequestViewModel(auth, repo, saved); runCurrent()
        vm.onNameChange("개인 초안"); vm.onPurposeChange("계획")
        auth.signOut(); advanceUntilIdle()
        assertEquals("", vm.state.value.name)
        assertFalse(vm.state.value.canSubmit)
        assertEquals(LoadState.Empty, vm.state.value.history)
        assertTrue(saved.keys().isEmpty())
        vm.submit(); assertTrue(repo.calls.isEmpty())
        auth.session.value = AuthSession.SignedIn(User("bob", "다른 계정")); advanceUntilIdle()
        assertEquals("", vm.state.value.name)
    }
    @Test fun historyFailureCanBeRetriedWithoutLosingDraft() = runTest {
        val auth = TestAuth(); val repo = TestRequests()
        repo.historyFailure = true
        val vm = ClubRequestViewModel(auth, repo, SavedStateHandle()); runCurrent()
        assertTrue(vm.state.value.history is LoadState.Error)
        vm.onNameChange("초안"); repo.historyFailure = false
        vm.reloadHistory(); runCurrent()
        assertEquals(LoadState.Empty, vm.state.value.history)
        assertEquals("초안", vm.state.value.name)
    }
    @Test fun restoredDraftNeverLeaksToAnotherAccount() = runTest {
        val saved = SavedStateHandle(mapOf("owner" to "someone-else", "name" to "비공개 초안", "pendingId" to "someone-else__id"))
        val vm = ClubRequestViewModel(TestAuth(), TestRequests(), saved); runCurrent()
        assertEquals("", vm.state.value.name)
        assertNull(saved.get<String>("pendingId"))
        assertEquals("alice", saved.get<String>("owner"))
    }
}

private class TestAuth : AuthRepository {
    override val session = MutableStateFlow<AuthSession>(AuthSession.SignedIn(User("alice", "승민")))
    override fun requestPhoneCode(phoneNumber: String, activity: Activity?): Flow<PhoneCodeEvent> = error("Unused")
    override suspend fun verifyPhoneCode(verificationId: String, code: String) = error("Unused")
    override suspend fun completeProfile(displayName: String) = error("Unused")
    override suspend fun signOut() { session.value = AuthSession.SignedOut }
}
private class TestRequests : ClubRequestRepository {
    var historyFailure = false
    var beforeSubmit: suspend () -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    val history = MutableStateFlow<List<ClubRequest>>(emptyList())
    val calls = mutableListOf<String>()
    private var nextId = 0
    override fun newRequestId() = "alice__${++nextId}"
    override suspend fun submitClubRequest(name: String, purpose: String, requestId: String): String {
        calls += requestId; beforeSubmit()
        if (history.value.none { it.id == requestId }) history.value += ClubRequest(requestId, "alice", name, purpose, RequestStatus.PENDING)
        afterCommit(); return requestId
    }
    override fun observeMyRequests(): Flow<List<ClubRequest>> = flow {
        if (historyFailure) throw RequestFailure(RequestFailureReason.NETWORK)
        emitAll(history)
    }
    override fun observePendingRequests() = history
    override suspend fun reviewRequest(id: String, status: RequestStatus) = error("Unused")
}
