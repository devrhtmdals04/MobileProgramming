package com.mope.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.mope.app.BuildConfig
import com.mope.app.MopeApplication
import com.mope.app.data.model.RequestStatus
import com.mope.app.data.remote.auth.FirebaseAuthRepository
import com.mope.app.data.remote.request.FirestoreClubRequestRepository
import com.mope.app.data.repository.RequestFailure
import com.mope.app.data.repository.RequestFailureReason
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
class FirestoreClubRequestRepositoryTest {
    private lateinit var student: FirestoreClubRequestRepository
    private lateinit var operator: FirestoreClubRequestRepository
    private lateinit var outsider: FirestoreClubRequestRepository

    @Before fun prepare() = runBlocking {
        withTimeout(60_000) {
            listOf(studentSdk, operatorSdk, outsiderSdk).forEach { sdk ->
                sdk.auth.signOut()
                FirebaseAuthRepository(sdk.auth, sdk.store).signUp("request-${UUID.randomUUID()}@example.com", "request123", "개설 요청")
            }
            // 로컬 Emulator fixture에만 운영 권한 부여. 제품 코드에서는 claim을 부여하지 않는다.
            request("http://10.0.2.2:9099/identitytoolkit.googleapis.com/v1/accounts:update", "POST", JSONObject()
                .put("localId", operatorSdk.auth.currentUser!!.uid)
                .put("customAttributes", JSONObject().put("operator", true).toString()))
            operatorSdk.auth.currentUser!!.getIdToken(true).await()
            student = FirestoreClubRequestRepository(studentSdk.auth, studentSdk.store)
            operator = FirestoreClubRequestRepository(operatorSdk.auth, operatorSdk.store)
            outsider = FirestoreClubRequestRepository(outsiderSdk.auth, outsiderSdk.store)
        }
    }
    @After fun signOut() { listOf(studentSdk, operatorSdk, outsiderSdk).forEach { it.auth.signOut() } }

    @Test fun submitRetryAndLiveReviewPreserveOriginalFields() = runBlocking {
        withTimeout(60_000) {
            val id = student.newRequestId()
            val mine = async { student.observeMyRequests().first { list -> list.any { it.id == id } } }
            val pending = async { operator.observePendingRequests().first { list -> list.any { it.id == id } } }
            val submissions = (0..1).map { async { student.submitClubRequest(" 사진부 ", " 함께 기록해요\n초보 환영 ", id) } }
            submissions.forEach { assertEquals(id, it.await()) }
            val original = mine.await().single { it.id == id }
            assertEquals("사진부", original.name)
            assertEquals(RequestStatus.PENDING, original.status)
            assertNotNull(original.createdAtMillis)
            assertNull(original.reviewedBy)
            assertTrue(pending.await().any { it.id == id })
            operator.reviewRequest(id, RequestStatus.APPROVED)
            val approved = student.observeMyRequests().first { list -> list.any { it.id == id && it.status == RequestStatus.APPROVED } }.single { it.id == id }
            assertEquals(original.createdAtMillis, approved.createdAtMillis)
            assertEquals(original.purpose, approved.purpose)
            assertEquals(operatorSdk.auth.currentUser!!.uid, approved.reviewedBy)
            assertNotNull(approved.reviewedAtMillis)
            assertFalse(operator.observePendingRequests().first().any { it.id == id })
            assertEquals(id, student.submitClubRequest("사진부", "함께 기록해요\n초보 환영", id))
            failure(RequestFailureReason.CONFLICT) { student.submitClubRequest("다른 이름", "목적", id) }
            failure(RequestFailureReason.CONFLICT) { operator.reviewRequest(id, RequestStatus.REJECTED) }
        }
    }
    @Test fun concurrentOppositeReviewsHaveExactlyOneWinner() = runBlocking {
        withTimeout(60_000) {
            val id = student.submitClubRequest("동시 검토", "하나만 처리")
            val results = listOf(RequestStatus.APPROVED, RequestStatus.REJECTED).map { status -> async {
                try { operator.reviewRequest(id, status); null } catch (failure: RequestFailure) { failure.reason }
            } }.map { it.await() }
            assertEquals(1, results.count { it == null })
            assertEquals("Concurrent review results: $results", 1, results.count { it == RequestFailureReason.CONFLICT })
        }
    }
    @Test fun ownershipValidationAndOrdinaryReviewerAreEnforced() = runBlocking {
        withTimeout(60_000) {
            val id = student.submitClubRequest("사진부", "기록")
            assertTrue(outsider.observeMyRequests().first().isEmpty())
            failure(RequestFailureReason.PERMISSION_DENIED) { outsider.observePendingRequests().first() }
            failure(RequestFailureReason.PERMISSION_DENIED) { outsider.reviewRequest(id, RequestStatus.APPROVED) }
            failure(RequestFailureReason.PERMISSION_DENIED) { student.reviewRequest(id, RequestStatus.APPROVED) }
            failure(RequestFailureReason.INVALID_INPUT) { student.submitClubRequest(" ", "목적") }
            failure(RequestFailureReason.INVALID_INPUT) { student.submitClubRequest("가".repeat(51), "목적") }
            failure(RequestFailureReason.INVALID_INPUT) { student.submitClubRequest("제목", "가".repeat(2001)) }
            failure(RequestFailureReason.INVALID_INPUT) { student.submitClubRequest("제목", "목적", "bad/id") }
            failure(RequestFailureReason.INVALID_INPUT) { operator.reviewRequest(id, RequestStatus.PENDING) }
            failure(RequestFailureReason.NOT_FOUND) { operator.reviewRequest("someone__missing", RequestStatus.APPROVED) }
            failure(RequestFailureReason.UNAUTHENTICATED) { outsider.submitClubRequest("제목", "목적", id) }
        }
    }
    @Test fun logoutEndsSubscriptionAndOldRequestCannotMoveToNewAccount() = runBlocking {
        withTimeout(60_000) {
            val oldId = student.newRequestId()
            val ready = CompletableDeferred<Unit>()
            val ended = async {
                try { student.observeMyRequests().collect { ready.complete(Unit) }; null }
                catch (failure: RequestFailure) { failure.reason }
            }
            ready.await()
            studentSdk.auth.signOut()
            assertEquals(RequestFailureReason.UNAUTHENTICATED, ended.await())
            failure(RequestFailureReason.UNAUTHENTICATED) { student.submitClubRequest("제목", "목적", oldId) }
            FirebaseAuthRepository(studentSdk.auth, studentSdk.store).signUp("new-${UUID.randomUUID()}@example.com", "request123", "새 계정")
            failure(RequestFailureReason.UNAUTHENTICATED) { student.submitClubRequest("제목", "목적", oldId) }
            val listening = CompletableDeferred<Unit>()
            val job = launch { student.observeMyRequests().collect { listening.complete(Unit) } }
            listening.await()
            job.cancelAndJoin()
            assertTrue(student.observeMyRequests().first().isEmpty())
        }
    }
    @Test fun invalidServerDataFailsInsteadOfPublishingAndAppUsesSharedSession() = runBlocking {
        withTimeout(60_000) {
            val id = student.submitClubRequest("손상 검증", "목적")
            request("http://10.0.2.2:8080/v1/projects/demo-mope/databases/(default)/documents/clubRequests/$id?updateMask.fieldPaths=status",
                "PATCH", JSONObject().put("fields", JSONObject().put("status", JSONObject().put("stringValue", "unknown"))))
            failure(RequestFailureReason.INVALID_DATA) { student.observeMyRequests().first() }
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MopeApplication
            val auth = requireNotNull(app.authRepository)
            val requests = requireNotNull(app.clubRequestRepository)
            try {
                auth.signUp("wired-${UUID.randomUUID()}@example.com", "request123", "앱 연결")
                val wiredId = requests.submitClubRequest("앱 요청", "공유 세션")
                assertTrue(requests.observeMyRequests().first().any { it.id == wiredId })
                auth.signOut()
                failure(RequestFailureReason.UNAUTHENTICATED) { requests.observeMyRequests().first() }
            } finally { auth.signOut() }
        }
    }
    private suspend fun failure(reason: RequestFailureReason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (actual: RequestFailure) { assertEquals(reason, actual.reason) }
    }
    private fun request(url: String, method: String, body: JSONObject) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer owner")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
        } finally { connection.disconnect() }
    }
    private data class Sdk(val auth: FirebaseAuth, val store: FirebaseFirestore)
    companion object {
        private lateinit var studentSdk: Sdk
        private lateinit var operatorSdk: Sdk
        private lateinit var outsiderSdk: Sdk
        @JvmStatic @BeforeClass fun initialize() {
            check(BuildConfig.BACKEND == "emulator")
            fun sdk(name: String): Sdk {
                val app = FirebaseApp.initializeApp(InstrumentationRegistry.getInstrumentation().targetContext,
                    FirebaseOptions.Builder().setProjectId("demo-mope").setApplicationId("1:1234567890:android:request-test")
                        .setApiKey("demo-mope-api-key").build(), name)
                val auth = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 9099) }
                val store = FirebaseFirestore.getInstance(app).apply {
                    useEmulator("10.0.2.2", 8080)
                    firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
                }
                return Sdk(auth, store)
            }
            studentSdk = sdk("request-student"); operatorSdk = sdk("request-operator"); outsiderSdk = sdk("request-outsider")
        }
        @JvmStatic @AfterClass fun terminate() = runBlocking {
            listOf(studentSdk, operatorSdk, outsiderSdk).forEach { it.store.terminate().await() }
        }
    }
}
