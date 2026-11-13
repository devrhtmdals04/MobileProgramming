package com.mope.app.data.remote.request

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.mope.app.data.model.ClubRequest
import com.mope.app.data.model.RequestStatus
import com.mope.app.data.repository.ClubRequestRepository
import com.mope.app.data.repository.RequestFailure
import com.mope.app.data.repository.RequestFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreClubRequestRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val sessionUid: () -> String? = { auth.currentUser?.uid },
) : ClubRequestRepository {
    override fun newRequestId(): String = "${requireUser()}__${UUID.randomUUID()}"

    override suspend fun submitClubRequest(name: String, purpose: String, requestId: String): String = operation {
        val title = name.trim()
        val body = purpose.trim()
        if (title.isBlank() || title.length > 50 || body.isBlank() || body.length > 2000) fail(RequestFailureReason.INVALID_INPUT)
        checkId(requestId)
        val uid = requireUser()
        if (requestId.substringBefore("__") != uid) fail(RequestFailureReason.UNAUTHENTICATED)
        val ref = firestore.collection("clubRequests").document(requestId)
        firestore.runTransaction { tx ->
            sameUser(uid)
            val old = tx.get(ref)
            if (old.exists()) {
                val request = decode(old)
                if (request.applicantId != uid || request.name != title || request.purpose != body) fail(RequestFailureReason.CONFLICT)
            } else {
                sameUser(uid)
                tx.set(ref, mapOf("applicantId" to uid, "name" to title, "purpose" to body,
                    "status" to "pending", "createdAt" to FieldValue.serverTimestamp(),
                    "reviewedBy" to null, "reviewedAt" to null))
            }
            Unit
        }.await()
        sameUser(uid)
        requestId
    }

    override fun observeMyRequests(): Flow<List<ClubRequest>> = observe { uid ->
        firestore.collection("clubRequests").whereEqualTo("applicantId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
    }

    override fun observePendingRequests(): Flow<List<ClubRequest>> = observe {
        // 권한은 users.role이 아닌 신뢰된 operator custom claim을 Rules에서 확인한다.
        firestore.collection("clubRequests").whereEqualTo("status", "pending").orderBy("createdAt")
    }

    override suspend fun reviewRequest(id: String, status: RequestStatus) = operation {
        checkId(id)
        if (status == RequestStatus.PENDING) fail(RequestFailureReason.INVALID_INPUT)
        val uid = requireUser()
        val ref = firestore.collection("clubRequests").document(id)
        try {
            firestore.runTransaction { tx ->
                sameUser(uid)
                val old = tx.get(ref)
                if (!old.exists()) fail(RequestFailureReason.NOT_FOUND)
                if (decode(old).status != RequestStatus.PENDING) fail(RequestFailureReason.CONFLICT)
                sameUser(uid)
                tx.update(ref, mapOf("status" to status.name.lowercase(), "reviewedBy" to uid,
                    "reviewedAt" to FieldValue.serverTimestamp()))
                Unit
            }.await()
        } catch (failure: FirebaseFirestoreException) {
            // 동시 검토의 패자는 transaction 재시도 전에 Rules의 pending 조건에서 거절될 수 있다.
            // 운영자에게만 서버 상태를 재확인해 충돌로 분류하며 성공으로 바꾸지는 않는다.
            if (failure.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw failure
            sameUser(uid)
            val claims = auth.currentUser?.getIdToken(false)?.await()?.claims
            sameUser(uid)
            if (claims?.get("operator") == true) {
                val latest = ref.get(Source.SERVER).await()
                sameUser(uid)
                if (latest.exists() && decode(latest).status != RequestStatus.PENDING) fail(RequestFailureReason.CONFLICT)
            }
            throw failure
        }
        sameUser(uid)
    }

    private fun observe(query: (String) -> Query): Flow<List<ClubRequest>> = callbackFlow<Pair<String, List<ClubRequest>>> {
        val uid = requireUser()
        val listener = query(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                sessionUid() != uid -> close(RequestFailure(RequestFailureReason.UNAUTHENTICATED))
                error != null -> close(mapped(error))
                snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites() -> try {
                    trySend(uid to snapshot.documents.map(::decode))
                } catch (failure: Exception) { close(mapped(failure)) }
            }
        }
        val authListener = FirebaseAuth.AuthStateListener {
            if (sessionUid() != uid) close(RequestFailure(RequestFailureReason.UNAUTHENTICATED))
        }
        auth.addAuthStateListener(authListener)
        awaitClose { listener.remove(); auth.removeAuthStateListener(authListener) }
    }.buffer(Channel.CONFLATED).map { (uid, requests) -> sameUser(uid); requests }

    private fun decode(doc: DocumentSnapshot): ClubRequest = try {
        val applicant = doc.getString("applicantId") ?: fail(RequestFailureReason.INVALID_DATA)
        val name = doc.getString("name") ?: fail(RequestFailureReason.INVALID_DATA)
        val purpose = doc.getString("purpose") ?: fail(RequestFailureReason.INVALID_DATA)
        val status = when (doc.getString("status")) {
            "pending" -> RequestStatus.PENDING
            "approved" -> RequestStatus.APPROVED
            "rejected" -> RequestStatus.REJECTED
            else -> fail(RequestFailureReason.INVALID_DATA)
        }
        val created = doc.getTimestamp("createdAt") ?: fail(RequestFailureReason.INVALID_DATA)
        val reviewer = doc.getString("reviewedBy")
        val reviewed = doc.getTimestamp("reviewedAt")
        if (applicant.isBlank() || doc.id.substringBefore("__") != applicant || name.isBlank() || name.length > 50
            || purpose.isBlank() || purpose.length > 2000
            || (status == RequestStatus.PENDING && (reviewer != null || reviewed != null))
            || (status != RequestStatus.PENDING && (reviewer.isNullOrBlank() || reviewed == null))) fail(RequestFailureReason.INVALID_DATA)
        ClubRequest(doc.id, applicant, name, purpose, status, created.toDate().time, reviewer, reviewed?.toDate()?.time)
    } catch (failure: RequestFailure) { throw failure }
    catch (failure: Exception) { throw RequestFailure(RequestFailureReason.INVALID_DATA, failure) }

    private fun checkId(id: String) {
        val parts = id.split("__")
        if (id.length > 256 || parts.size != 2 || parts.any { it.isBlank() || it.length > 128 }
            || '/' in id || id.any { it.isISOControl() }) fail(RequestFailureReason.INVALID_INPUT)
    }
    private fun requireUser(): String = sessionUid() ?: fail(RequestFailureReason.UNAUTHENTICATED)
    private fun sameUser(uid: String) { if (sessionUid() != uid) fail(RequestFailureReason.UNAUTHENTICATED) }
    private fun fail(reason: RequestFailureReason): Nothing = throw RequestFailure(reason)
    private suspend fun <T> operation(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { throw mapped(failure) }
}

private fun mapped(failure: Exception): RequestFailure {
    generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<RequestFailure>().firstOrNull()?.let { return it }
    val reason = when ((failure as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> RequestFailureReason.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> RequestFailureReason.UNAUTHENTICATED
        FirebaseFirestoreException.Code.NOT_FOUND -> RequestFailureReason.NOT_FOUND
        FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED -> RequestFailureReason.NETWORK
        else -> RequestFailureReason.UNKNOWN
    }
    return RequestFailure(reason, failure)
}
