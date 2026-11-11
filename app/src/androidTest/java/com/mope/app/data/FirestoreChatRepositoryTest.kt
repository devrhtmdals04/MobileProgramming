package com.mope.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MemoryCacheSettings
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import com.mope.app.data.remote.auth.FirebaseAuthRepository
import com.mope.app.data.remote.chat.FirestoreChatRepository
import com.mope.app.data.repository.ChatFailure
import com.mope.app.data.repository.ChatFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FirestoreChatRepositoryTest {
    private val clubId = "chat-${UUID.randomUUID()}"
    private lateinit var student: FirestoreChatRepository
    private lateinit var admin: FirestoreChatRepository
    private lateinit var outsider: FirestoreChatRepository

    @Before fun prepare() = runBlocking {
        withTimeout(60_000) {
            listOf(studentSdk, adminSdk, outsiderSdk).forEachIndexed { index, sdk ->
                sdk.auth.signOut()
                FirebaseAuthRepository(sdk.auth, sdk.store).signUp("$index-${UUID.randomUUID()}@example.com", "chat12345", "채팅 $index")
            }
            // 테스트 fixture만 Emulator의 owner 권한으로 준비. Repository 요청은 실제 Rules 적용.
            val connection = URL("http://10.0.2.2:8080/v1/projects/demo-mope/databases/(default)/documents/clubs/$clubId")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PATCH"
                connection.setRequestProperty("Authorization", "Bearer owner")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                val body = JSONObject().put("fields", JSONObject().put("adminId", JSONObject().put("stringValue", adminSdk.auth.currentUser!!.uid)))
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                assertEquals("Could not seed club fixture", 200, connection.responseCode)
            } finally { connection.disconnect() }
            student = FirestoreChatRepository(studentSdk.auth, studentSdk.store)
            admin = FirestoreChatRepository(adminSdk.auth, adminSdk.store)
            outsider = FirestoreChatRepository(outsiderSdk.auth, outsiderSdk.store)
        }
    }

    @After fun signOut() {
        listOf(studentSdk, adminSdk, outsiderSdk).forEach { it.auth.signOut() }
    }

    @Test fun twoAccountsExchangeMessagesAndReconnect() = runBlocking {
        withTimeout(60_000) {
            val studentRooms = async { student.observeMyRooms().first { rooms -> rooms.any { it.clubId == clubId } } }
            val room = student.getOrCreateRoom(clubId)
            assertTrue(studentRooms.await().any { it.id == room })
            val adminRooms = admin.observeMyRooms().first { rooms -> rooms.any { it.id == room } }
            assertEquals(clubId, adminRooms.first { it.id == room }.clubId)
            val studentMessage = student.newMessageId()
            val received = async { admin.observeMessages(room).first { messages -> messages.any { it.id == studentMessage } } }
            student.sendMessage(room, "가입 문의합니다\n신입생입니다", studentMessage)
            assertEquals(studentSdk.auth.currentUser!!.uid, received.await().single { it.id == studentMessage }.senderId)
            val adminMessage = admin.newMessageId()
            admin.sendMessage(room, "환영합니다", adminMessage)
            val messages = student.observeMessages(room).first { it.size == 2 }
            assertEquals(listOf("가입 문의합니다\n신입생입니다", "환영합니다"), messages.map { it.text })
            assertTrue(messages.all { it.createdAtMillis != null })
            val reconnected = FirestoreChatRepository(studentSdk.auth, studentSdk.store)
            assertEquals(messages, reconnected.observeMessages(room).first { it.size == 2 })
        }
    }

    @Test fun concurrentRoomAndMessageRetriesDoNotDuplicate() = runBlocking {
        withTimeout(60_000) {
            val first = async { student.getOrCreateRoom(clubId) }
            val second = async { student.getOrCreateRoom(clubId) }
            val room = first.await()
            assertEquals(room, second.await())
            val id = student.newMessageId()
            val send1 = async { student.sendMessage(room, "같은 요청", id) }
            val send2 = async { student.sendMessage(room, "같은 요청", id) }
            send1.await(); send2.await()
            assertEquals(1, student.observeMessages(room).first { it.isNotEmpty() }.size)
            failure(ChatFailureReason.CONFLICT) { student.sendMessage(room, "다른 요청", id) }
            failure(ChatFailureReason.CONFLICT) { admin.sendMessage(room, "같은 요청", id) }
        }
    }

    @Test fun nonparticipantCannotReadOrSendAndInvalidInputFails() = runBlocking {
        withTimeout(60_000) {
            val room = student.getOrCreateRoom(clubId)
            failure(ChatFailureReason.PERMISSION_DENIED) { outsider.observeMessages(room).first() }
            failure(ChatFailureReason.PERMISSION_DENIED) { outsider.sendMessage(room, "침입") }
            failure(ChatFailureReason.SELF_CHAT) { admin.getOrCreateRoom(clubId) }
            failure(ChatFailureReason.NOT_FOUND) { student.getOrCreateRoom("missing-${UUID.randomUUID()}") }
            failure(ChatFailureReason.INVALID_INPUT) { student.getOrCreateRoom("bad__club") }
            failure(ChatFailureReason.INVALID_INPUT) { student.sendMessage(room, "   ") }
            failure(ChatFailureReason.INVALID_INPUT) { student.sendMessage(room, "가".repeat(2001)) }
            failure(ChatFailureReason.INVALID_INPUT) { student.sendMessage(room, "본문", "bad/id") }
        }
    }

    @Test fun logoutEndsSubscriptionsAndNewLoginDoesNotInheritThem() = runBlocking {
        withTimeout(60_000) {
            val room = student.getOrCreateRoom(clubId)
            val readyRooms = CompletableDeferred<Unit>()
            val readyMessages = CompletableDeferred<Unit>()
            val rooms = async {
                try { student.observeMyRooms().collect { readyRooms.complete(Unit) }; error("Expected auth failure") }
                catch (failure: ChatFailure) { failure.reason }
            }
            val messages = async {
                try { student.observeMessages(room).collect { readyMessages.complete(Unit) }; error("Expected auth failure") }
                catch (failure: ChatFailure) { failure.reason }
            }
            readyRooms.await(); readyMessages.await()
            studentSdk.auth.signOut()
            assertEquals(ChatFailureReason.UNAUTHENTICATED, rooms.await())
            assertEquals(ChatFailureReason.UNAUTHENTICATED, messages.await())
            failure(ChatFailureReason.UNAUTHENTICATED) { student.sendMessage(room, "로그아웃") }
            FirebaseAuthRepository(studentSdk.auth, studentSdk.store).signUp("new-${UUID.randomUUID()}@example.com", "chat12345", "다른 사용자")
            assertTrue(student.observeMyRooms().first().isEmpty())
            failure(ChatFailureReason.PERMISSION_DENIED) { student.observeMessages(room).first() }
        }
    }

    @Test fun cancelledCollectorStopsAndAnotherSubscriptionCanReconnect() = runBlocking {
        withTimeout(60_000) {
            val room = student.getOrCreateRoom(clubId)
            val ready = CompletableDeferred<Unit>()
            val job = launch { student.observeMessages(room).collect { ready.complete(Unit) } }
            ready.await()
            job.cancelAndJoin()
            admin.sendMessage(room, "재접속 메시지")
            assertEquals("재접속 메시지", student.observeMessages(room).first { it.isNotEmpty() }.last().text)
        }
    }

    @Test fun latestFiftyMessagesUseDocumentIdToBreakTimestampTies() = runBlocking {
        withTimeout(60_000) {
            val room = student.getOrCreateRoom(clubId)
            val messages = studentSdk.store.collection("chatRooms").document(room).collection("messages")
            val batch = studentSdk.store.batch()
            for (index in 0 until 55) {
                batch.set(messages.document("id-${index.toString().padStart(3, '0')}"), mapOf(
                    "senderId" to studentSdk.auth.currentUser!!.uid, "text" to "메시지 $index",
                    "createdAt" to FieldValue.serverTimestamp()))
            }
            batch.commit().await()
            val result = admin.observeMessages(room).first { it.size == 50 }
            assertEquals("id-005", result.first().id)
            assertEquals("id-054", result.last().id)
            assertEquals(result.map { it.id }.sorted(), result.map { it.id })
        }
    }

    @Test fun applicationSharesAuthAndChatBackend() = runBlocking {
        withTimeout(60_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MopeApplication
            val auth = requireNotNull(app.authRepository)
            val chat = requireNotNull(app.chatRepository)
            try {
                auth.signUp("wired-${UUID.randomUUID()}@example.com", "chat12345", "연결 확인")
                val room = chat.getOrCreateRoom(clubId)
                chat.sendMessage(room, "앱 연결 메시지")
                assertEquals("앱 연결 메시지", admin.observeMessages(room).first { it.isNotEmpty() }.last().text)
                auth.signOut()
                failure(ChatFailureReason.UNAUTHENTICATED) { chat.observeMyRooms().first() }
            } finally { auth.signOut() }
        }
    }

    private suspend fun failure(reason: ChatFailureReason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (actual: ChatFailure) { assertEquals(reason, actual.reason) }
    }

    private data class Sdk(val auth: FirebaseAuth, val store: FirebaseFirestore)
    companion object {
        private lateinit var studentSdk: Sdk
        private lateinit var adminSdk: Sdk
        private lateinit var outsiderSdk: Sdk
        @JvmStatic @BeforeClass fun initialize() {
            check(BuildConfig.BACKEND == "emulator")
            fun sdk(name: String): Sdk {
                val app = FirebaseApp.initializeApp(InstrumentationRegistry.getInstrumentation().targetContext,
                    FirebaseOptions.Builder().setProjectId("demo-mope")
                        .setApplicationId("1:1234567890:android:chat-test")
                        .setApiKey("demo-mope-api-key").build(), name)
                val auth = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 9099) }
                val store = FirebaseFirestore.getInstance(app).apply {
                    useEmulator("10.0.2.2", 8080)
                    firestoreSettings = FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
                }
                return Sdk(auth, store)
            }
            studentSdk = sdk("chat-student"); adminSdk = sdk("chat-admin"); outsiderSdk = sdk("chat-outsider")
        }
        @JvmStatic @AfterClass fun terminate() = runBlocking {
            listOf(studentSdk, adminSdk, outsiderSdk).forEach { it.store.terminate().await() }
        }
    }
}
