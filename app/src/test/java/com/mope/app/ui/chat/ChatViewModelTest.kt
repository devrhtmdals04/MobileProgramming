package com.mope.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.Message
import com.mope.app.data.model.User
import com.mope.app.data.repository.ChatFailure
import com.mope.app.data.repository.ChatFailureReason
import com.mope.app.ui.MainDispatcherRule
import com.mope.app.ui.screens.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = ChatAuthFixture()
    private val chat = ChatFixture(auth)
    private val stores = mutableListOf<ViewModelStore>()
    private fun <T : ViewModel> keep(vm: T): T {
        stores.add(ViewModelStore().apply { put("vm", vm) })
        return vm
    }
    private fun room(handle: SavedStateHandle = SavedStateHandle(), target: ChatTarget = ChatTarget.Club("club")) =
        keep(ChatRoomViewModel(target, auth, chat, handle))
    @After fun clear() { stores.forEach { it.clear() } }

    @Test fun listLoadsEmptyThenContentAndClearsOnAccountChange() = runTest {
        val vm = keep(ChatListViewModel(auth, chat))
        assertTrue(vm.state.value.isLoading)
        runCurrent()
        assertTrue(vm.state.value.isEmpty)
        chat.rooms().emit(Result.success(listOf(ChatRoom("club__alice", "club", listOf("alice", "admin")))))
        runCurrent()
        assertEquals(1, vm.state.value.rooms.size)
        auth.users.value = User("bob", "새 계정")
        runCurrent()
        assertEquals("bob", vm.state.value.userId)
        assertTrue(vm.state.value.rooms.isEmpty())
        assertEquals(1, chat.activeRoomListeners)
        auth.signOut()
        runCurrent()
        assertTrue(vm.state.value.rooms.isEmpty())
        assertTrue(vm.state.value.error!!.requiresSignIn)
        assertEquals(0, chat.activeRoomListeners)
    }

    @Test fun listRetryReplacesFailedSubscriptionWithoutDuplicatingIt() = runTest {
        val vm = keep(ChatListViewModel(auth, chat))
        runCurrent()
        chat.rooms().emit(Result.failure(ChatFailure(ChatFailureReason.NETWORK)))
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals(0, chat.activeRoomListeners)
        chat.rooms().emit(Result.success(emptyList()))
        vm.retry(); vm.retry()
        runCurrent()
        assertTrue(vm.state.value.isEmpty)
        assertEquals(1, chat.activeRoomListeners)
    }

    @Test fun clubAndExistingRoomEntryHaveDifferentOpeningBehavior() = runTest {
        val club = room()
        assertEquals(ChatConnection.RESTORING, club.state.value.connection)
        runCurrent()
        assertEquals(listOf("club"), chat.openedClubs)
        assertEquals(ChatConnection.READY, club.state.value.connection)
        assertEquals("club__alice", club.state.value.roomId)
        val existing = room(target = ChatTarget.Room("other__alice"))
        runCurrent()
        assertEquals(1, chat.openedClubs.size)
        assertEquals("other__alice", existing.state.value.roomId)
    }

    @Test fun openingFailureCanRetryAndBlankOrLongInputDoesNotAllocateMessageId() = runTest {
        chat.openFailure = ChatFailure(ChatFailureReason.NETWORK)
        val vm = room()
        runCurrent()
        assertEquals(ChatConnection.FAILED, vm.state.value.connection)
        chat.openFailure = null
        vm.retryConnection()
        runCurrent()
        vm.onDraftChange("  "); vm.sendDraft()
        vm.onDraftChange("가".repeat(2001)); vm.sendDraft()
        runCurrent()
        assertNotNull(vm.state.value.inputError)
        assertEquals(0, chat.generatedIds)
        assertTrue(chat.sent.isEmpty())
        vm.onDraftChange("가".repeat(2000))
        assertTrue(vm.state.value.canSend)
    }

    @Test fun duplicateSubmitIsBlockedAndNewDraftSurvivesSuccessfulSend() = runTest {
        val gate = CompletableDeferred<Unit>()
        chat.sendAction = { gate.await() }
        val vm = room()
        runCurrent()
        vm.onDraftChange("첫 줄\n둘째 줄")
        vm.sendDraft(); vm.sendDraft()
        runCurrent()
        assertEquals(1, chat.generatedIds)
        assertEquals(1, chat.sent.size)
        assertTrue(vm.state.value.isSending)
        vm.onDraftChange("다음 메시지")
        vm.sendDraft()
        assertEquals(1, chat.generatedIds)
        gate.complete(Unit)
        runCurrent()
        assertEquals("다음 메시지", vm.state.value.draft)
        assertEquals(1, vm.state.value.timeline.size)
        assertEquals(ChatDelivery.SENT, vm.state.value.timeline.single().delivery)
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test fun failedMessageRetryUsesOriginalIdAndBodyAndPreservesComposer() = runTest {
        chat.sendAction = { throw ChatFailure(ChatFailureReason.NETWORK) }
        val vm = room()
        runCurrent()
        vm.onDraftChange("원문"); vm.sendDraft()
        runCurrent()
        val id = vm.state.value.pending.single().id
        assertEquals(ChatDelivery.FAILED, vm.state.value.timeline.single().delivery)
        vm.onDraftChange("작성 중인 새 문장")
        chat.sendAction = {}
        vm.retryMessage(id); vm.retryMessage(id)
        runCurrent()
        assertEquals(listOf(id, id), chat.sent.map { it.id })
        assertTrue(chat.sent.all { it.text == "원문" })
        assertEquals(1, chat.generatedIds)
        assertEquals("작성 중인 새 문장", vm.state.value.draft)
        assertEquals(1, vm.state.value.timeline.size)
    }

    @Test fun savedStateRestoresInterruptedSendAsExplicitRetryWithSameId() = runTest {
        val handle = SavedStateHandle()
        val gate = CompletableDeferred<Unit>()
        chat.sendAction = { gate.await() }
        val old = room(handle)
        runCurrent()
        old.onDraftChange("복원할 메시지"); old.sendDraft()
        runCurrent()
        val id = old.state.value.pending.single().id
        old.onDraftChange("작성 중인 문장")
        val snapshot = handle.keys().associateWith { key -> ArrayList(handle.get<ArrayList<String>>(key)!!) }
        clear()
        runCurrent()
        chat.sendAction = {}
        val restored = room(SavedStateHandle(snapshot))
        runCurrent()
        assertEquals("작성 중인 문장", restored.state.value.draft)
        assertEquals(id, restored.state.value.pending.single().id)
        assertEquals(ChatDelivery.FAILED, restored.state.value.pending.single().delivery)
        assertEquals(1, chat.sent.size) // 복원만으로 자동 재전송하지 않는다.
        restored.retryMessage(id)
        runCurrent()
        assertEquals(listOf(id, id), chat.sent.map { it.id })
    }

    @Test fun retryConnectionKeepsFailedOutboxAndReplacesMessageListener() = runTest {
        chat.sendAction = { throw ChatFailure(ChatFailureReason.NETWORK) }
        val vm = room()
        runCurrent()
        vm.onDraftChange("실패한 메시지"); vm.sendDraft()
        runCurrent()
        val id = vm.state.value.pending.single().id
        chat.messages().emit(Result.failure(ChatFailure(ChatFailureReason.NETWORK)))
        runCurrent()
        assertEquals(ChatConnection.FAILED, vm.state.value.connection)
        assertEquals(0, chat.activeMessageListeners)
        chat.messages().emit(Result.success(emptyList()))
        vm.retryConnection(); vm.retryConnection()
        runCurrent()
        assertEquals(id, vm.state.value.pending.single().id)
        assertEquals(1, chat.activeMessageListeners)
        chat.sendAction = {}
        vm.retryMessage(id)
        runCurrent()
        assertEquals(listOf(id, id), chat.sent.map { it.id })
    }

    @Test fun accountChangeRejectsLateCompletionAndLogoutErasesSavedPrivateData() = runTest {
        val gate = CompletableDeferred<Unit>()
        chat.sendAction = {
            try { gate.await() }
            catch (_: CancellationException) { withContext(NonCancellable) { gate.await() } }
        }
        val handle = SavedStateHandle()
        val vm = room(handle)
        runCurrent()
        vm.onDraftChange("이전 계정 내용"); vm.sendDraft()
        runCurrent()
        auth.users.value = User("bob", "새 계정")
        runCurrent()
        assertEquals("bob", vm.state.value.userId)
        assertEquals("club__bob", vm.state.value.roomId)
        assertTrue(vm.state.value.timeline.isEmpty())
        assertEquals("", vm.state.value.draft)
        gate.complete(Unit)
        runCurrent()
        assertEquals("bob", vm.state.value.userId)
        assertTrue(vm.state.value.timeline.isEmpty())
        auth.signOut()
        runCurrent()
        assertEquals(ChatConnection.SIGNED_OUT, vm.state.value.connection)
        assertTrue(handle.keys().isEmpty())
        assertEquals(0, chat.activeMessageListeners)
    }

    @Test fun differentOwnerOrTargetCannotRestorePreviousDraftOrOutbox() = runTest {
        chat.sendAction = { throw ChatFailure(ChatFailureReason.NETWORK) }
        val handle = SavedStateHandle()
        val old = room(handle)
        runCurrent()
        old.onDraftChange("비공개"); old.sendDraft()
        runCurrent()
        val snapshot = handle.keys().associateWith { key -> ArrayList(handle.get<ArrayList<String>>(key)!!) }
        val otherTarget = room(SavedStateHandle(snapshot), ChatTarget.Room("other__alice"))
        runCurrent()
        assertTrue(otherTarget.state.value.timeline.isEmpty())
        auth.users.value = User("bob", "다른 계정")
        val otherOwner = room(SavedStateHandle(snapshot))
        runCurrent()
        assertTrue(otherOwner.state.value.timeline.isEmpty())
        assertEquals("", otherOwner.state.value.draft)
    }

    @Test fun permissionFailureClearsHistoryAndStopsStream() = runTest {
        val handle = SavedStateHandle()
        val vm = room(handle)
        runCurrent()
        chat.messages().emit(Result.success(listOf(Message("private", "admin", "비공개 대화", 1000L))))
        runCurrent()
        vm.onDraftChange("작성 중")
        chat.sendAction = { throw ChatFailure(ChatFailureReason.PERMISSION_DENIED) }
        vm.sendDraft()
        runCurrent()
        assertEquals(ChatConnection.FAILED, vm.state.value.connection)
        assertTrue(vm.state.value.timeline.isEmpty())
        assertTrue(handle.keys().isEmpty())
        assertEquals(0, chat.activeMessageListeners)
        chat.messages().emit(Result.success(listOf(Message("late", "admin", "늦은 응답", 1001L))))
        runCurrent()
        assertTrue(vm.state.value.timeline.isEmpty())
    }

    @Test fun serverEchoAndFailedLocalRowShareOneTimelineEntry() = runTest {
        chat.sendAction = { request ->
            chat.messages().emit(Result.success(listOf(Message(request.id, "alice", request.text, null))))
            throw ChatFailure(ChatFailureReason.NETWORK)
        }
        val vm = room()
        runCurrent()
        vm.onDraftChange("같은 행"); vm.sendDraft()
        runCurrent()
        assertEquals(1, vm.state.value.timeline.size)
        assertEquals(ChatDelivery.FAILED, vm.state.value.timeline.single().delivery)
        chat.sendAction = {}
        vm.retryMessage(vm.state.value.pending.single().id)
        runCurrent()
        assertEquals(1, vm.state.value.timeline.size)
        assertEquals(ChatDelivery.SENT, vm.state.value.timeline.single().delivery)
    }

    @Test fun clearingScreenViewModelsCancelsAllSubscriptionsAndSend() = runTest {
        val gate = CompletableDeferred<Unit>()
        chat.sendAction = { gate.await() }
        keep(ChatListViewModel(auth, chat))
        val vm = room()
        runCurrent()
        vm.onDraftChange("화면 종료"); vm.sendDraft()
        runCurrent()
        assertEquals(1, chat.activeRoomListeners)
        assertEquals(1, chat.activeMessageListeners)
        clear()
        runCurrent()
        assertEquals(0, chat.activeRoomListeners)
        assertEquals(0, chat.activeMessageListeners)
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.timeline.none { it.delivery == ChatDelivery.SENT })
    }
}
