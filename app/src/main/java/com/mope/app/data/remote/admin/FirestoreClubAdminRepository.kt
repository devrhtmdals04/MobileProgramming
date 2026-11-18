package com.mope.app.data.remote.admin

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.mope.app.data.model.Club
import com.mope.app.data.model.RequestStatus
import com.mope.app.data.repository.*
import com.mope.app.domain.ClubAdminInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirestoreClubAdminRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val sessionUid: () -> String? = { auth.currentUser?.uid },
) : ClubAdminRepository {
    override suspend fun isOperator(): Boolean = operation {
        val uid = requireUser()
        val claims = auth.currentUser!!.getIdToken(false).await().claims
        val registry = firestore.collection("operators").document(uid).get(Source.SERVER).await()
        sameUser(uid)
        claims["operator"] == true || registry.getBoolean("enabled") == true
    }

    override fun observeMyApplications() = applications {
        firestore.collection("clubAdminRequests").whereEqualTo("applicantId", it)
    }
    override fun observePendingApplications() = applications {
        firestore.collection("clubAdminRequests").whereEqualTo("status", "pending")
    }
    private fun applications(query: (String) -> Query): Flow<List<ClubAdminApplication>> = flow {
        val uid = requireUser()
        emitAll(listen(query(uid), uid).map { docs -> docs.map(::application).sortedByDescending { it.createdAtMillis } })
    }

    // No private documents are read while exploring as a guest.
    override fun observeLinkedClubs(): Flow<List<Club>> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(sessionUid()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList()) else listen(firestore.collection("clubs"), uid).map { docs ->
            docs.filter { it.id.matches(Regex("ssu-[1-9][0-9]{0,8}")) }.map(::club)
        }
    }

    override suspend fun apply(club: Club, reason: String) = operation {
        ClubAdminInput.sourceUrl(club.id)
        val body = ClubAdminInput.reason(reason)
        val uid = requireUser()
        val ref = firestore.collection("clubAdminRequests").document("${club.id}__$uid")
        val clubRef = firestore.collection("clubs").document(club.id)
        firestore.runTransaction { tx ->
            sameUser(uid)
            val existing = tx.get(ref)
            val linked = tx.get(clubRef)
            if (linked.exists() && !linked.getString("adminId").isNullOrBlank()) fail(RequestFailureReason.CONFLICT)
            if (!existing.exists()) {
                tx.set(ref, mapOf("clubId" to club.id, "clubName" to club.name, "applicantId" to uid,
                    "reason" to body, "status" to "pending", "createdAt" to FieldValue.serverTimestamp(),
                    "reviewedBy" to null, "reviewedAt" to null, "verifiedChannel" to "", "reviewNote" to ""))
            } else {
                val old = application(existing)
                when (old.status) {
                    RequestStatus.PENDING -> if (old.reason != body) fail(RequestFailureReason.CONFLICT)
                    RequestStatus.APPROVED -> fail(RequestFailureReason.CONFLICT)
                    RequestStatus.REJECTED -> tx.update(ref, mapOf("reason" to body, "status" to "pending",
                        "reviewedBy" to null, "reviewedAt" to null, "verifiedChannel" to "", "reviewNote" to ""))
                }
            }
            Unit
        }.await()
        sameUser(uid)
    }

    override suspend fun review(applicationId: String, approve: Boolean, officialClub: Club?, channel: String, note: String) = operation {
        val reviewer = requireUser()
        val explanation = ClubAdminInput.reason(note)
        val verified = if (approve) ClubAdminInput.channel(channel) else ""
        if (!isOperator()) fail(RequestFailureReason.PERMISSION_DENIED)
        if (applicationId.contains('/') || applicationId.length !in 1..256) fail(RequestFailureReason.INVALID_INPUT)
        val ref = firestore.collection("clubAdminRequests").document(applicationId)
        firestore.runTransaction { tx ->
            sameUser(reviewer)
            val snapshot = tx.get(ref)
            if (!snapshot.exists()) fail(RequestFailureReason.NOT_FOUND)
            val request = application(snapshot)
            if (request.applicantId == reviewer) fail(RequestFailureReason.PERMISSION_DENIED)
            if (request.status != RequestStatus.PENDING) fail(RequestFailureReason.CONFLICT)
            val clubRef = firestore.collection("clubs").document(request.clubId)
            val linked = tx.get(clubRef)
            if (approve) {
                val source = officialClub ?: fail(RequestFailureReason.INVALID_INPUT)
                val canonical = ClubAdminInput.sourceUrl(source.id)
                if (source.id != request.clubId || source.sourceUrl != canonical) fail(RequestFailureReason.INVALID_INPUT)
                if (linked.exists() && !linked.getString("adminId").isNullOrBlank()) fail(RequestFailureReason.CONFLICT)
                tx.set(clubRef, mapOf("name" to source.name, "category" to source.category,
                    "description" to source.description, "recruitment" to source.recruitment,
                    "sourceUrl" to canonical, "adminId" to request.applicantId, "updatedAt" to FieldValue.serverTimestamp()))
            }
            tx.update(ref, mapOf("status" to if (approve) "approved" else "rejected", "reviewedBy" to reviewer,
                "reviewedAt" to FieldValue.serverTimestamp(), "verifiedChannel" to verified, "reviewNote" to explanation))
            Unit
        }.await()
        sameUser(reviewer)
    }

    override suspend fun updateClub(clubId: String, description: String, recruitment: String) = operation {
        ClubAdminInput.sourceUrl(clubId)
        val uid = requireUser()
        val body = ClubAdminInput.description(description)
        val announcement = ClubAdminInput.recruitment(recruitment)
        val ref = firestore.collection("clubs").document(clubId)
        firestore.runTransaction { tx ->
            sameUser(uid)
            val old = tx.get(ref)
            if (!old.exists() || old.getString("adminId") != uid) fail(RequestFailureReason.PERMISSION_DENIED)
            tx.update(ref, mapOf("description" to body, "recruitment" to announcement, "updatedAt" to FieldValue.serverTimestamp()))
            Unit
        }.await()
        sameUser(uid)
    }

    private fun listen(query: Query, uid: String): Flow<List<DocumentSnapshot>> = callbackFlow {
        val listener = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                sessionUid() != uid -> close(RequestFailure(RequestFailureReason.UNAUTHENTICATED))
                error != null -> close(mapped(error))
                snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites() -> trySend(snapshot.documents)
            }
        }
        val authListener = FirebaseAuth.AuthStateListener {
            if (sessionUid() != uid) close(RequestFailure(RequestFailureReason.UNAUTHENTICATED))
        }
        auth.addAuthStateListener(authListener)
        awaitClose { listener.remove(); auth.removeAuthStateListener(authListener) }
    }.map { sameUser(uid); it }

    private fun application(doc: DocumentSnapshot): ClubAdminApplication {
        val id = doc.getString("clubId") ?: fail(RequestFailureReason.INVALID_DATA)
        ClubAdminInput.sourceUrl(id)
        val status = when (doc.getString("status")) {
            "pending" -> RequestStatus.PENDING
            "approved" -> RequestStatus.APPROVED
            "rejected" -> RequestStatus.REJECTED
            else -> fail(RequestFailureReason.INVALID_DATA)
        }
        val applicant = doc.getString("applicantId") ?: fail(RequestFailureReason.INVALID_DATA)
        if (doc.id != "${id}__$applicant") fail(RequestFailureReason.INVALID_DATA)
        return ClubAdminApplication(doc.id, id, doc.getString("clubName") ?: fail(RequestFailureReason.INVALID_DATA),
            applicant, doc.getString("reason") ?: fail(RequestFailureReason.INVALID_DATA), status,
            doc.getTimestamp("createdAt")?.toDate()?.time ?: fail(RequestFailureReason.INVALID_DATA),
            doc.getString("reviewedBy"), doc.getTimestamp("reviewedAt")?.toDate()?.time,
            doc.getString("verifiedChannel").orEmpty(), doc.getString("reviewNote").orEmpty())
    }
    private fun club(doc: DocumentSnapshot) = Club(doc.id,
        doc.getString("name") ?: fail(RequestFailureReason.INVALID_DATA),
        doc.getString("category") ?: fail(RequestFailureReason.INVALID_DATA),
        doc.getString("description") ?: fail(RequestFailureReason.INVALID_DATA),
        doc.getString("recruitment") ?: fail(RequestFailureReason.INVALID_DATA),
        doc.getString("adminId") ?: fail(RequestFailureReason.INVALID_DATA),
        doc.getString("sourceUrl") ?: fail(RequestFailureReason.INVALID_DATA))

    private fun requireUser() = sessionUid() ?: fail(RequestFailureReason.UNAUTHENTICATED)
    private fun sameUser(uid: String) { if (sessionUid() != uid) fail(RequestFailureReason.UNAUTHENTICATED) }
    private fun fail(reason: RequestFailureReason): Nothing = throw RequestFailure(reason)
    private suspend fun <T> operation(block: suspend () -> T): T = try { block() }
    catch (failure: CancellationException) { throw failure }
    catch (failure: Exception) { throw mapped(failure) }
}

private fun mapped(failure: Exception): RequestFailure {
    if (failure is RequestFailure) return failure
    val reason = when ((failure as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> RequestFailureReason.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAVAILABLE -> RequestFailureReason.NETWORK
        else -> RequestFailureReason.UNKNOWN
    }
    return RequestFailure(reason, failure)
}
