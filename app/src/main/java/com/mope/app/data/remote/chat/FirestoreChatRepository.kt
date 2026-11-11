package com.mope.app.data.remote.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.mope.app.data.model.ChatRoom
import com.mope.app.data.model.ChatContext
import com.mope.app.data.model.PublicChatContact
import com.mope.app.data.model.Message
import com.mope.app.data.repository.ChatFailure
import com.mope.app.data.repository.ChatFailureReason
import com.mope.app.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreChatRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val sessionUid: () -> String? = { auth.currentUser?.uid },
) : ChatRepository {
    override fun newMessageId(): String = UUID.randomUUID().toString()

    override suspend fun getRoomContext(roomId: String): ChatContext = operation {
        checkRoomId(roomId)
        val uid = requireUser()
        val document = firestore.collection("chatRooms").document(roomId).get(Source.SERVER).await()
        requireSameUser(uid)
        if (!document.exists()) throw ChatFailure(ChatFailureReason.NOT_FOUND)
        val room = decodeRoom(document)
        if (uid !in room.participantIds) throw ChatFailure(ChatFailureReason.PERMISSION_DENIED)
        val adminId = document.getString("adminId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val clubRef = firestore.collection("clubs").document(room.clubId)
        val club = clubRef.get(Source.SERVER).await()
        requireSameUser(uid)
        if (!club.exists()) throw ChatFailure(ChatFailureReason.NOT_FOUND)
        val contactDoc = clubRef.collection("publicContacts").document(adminId).get(Source.SERVER).await()
        requireSameUser(uid)
        decode {
            val name = club.getString("name") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
            if (name.isBlank() || name.length > 50) throw ChatFailure(ChatFailureReason.INVALID_DATA)
            val title = club.getString("recruitmentTitle")
            if (title != null && (title.isBlank() || title.length > 100)) throw ChatFailure(ChatFailureReason.INVALID_DATA)
            val contact = if (!contactDoc.exists()) null else {
                val displayName = contactDoc.getString("displayName") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
                val hint = contactDoc.getString("responseHint")
                val updatedAt = contactDoc.getTimestamp("updatedAt") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
                if (displayName.isBlank() || displayName.length > 30 || (hint != null && (hint.isBlank() || hint.length > 120))) {
                    throw ChatFailure(ChatFailureReason.INVALID_DATA)
                }
                PublicChatContact(adminId, displayName, hint, updatedAt.toDate().time)
            }
            ChatContext(roomId, room.clubId, name, adminId, title,
                club.getTimestamp("recruitmentDeadlineAt")?.toDate()?.time, contact)
        }
    }

    override fun observeMyRooms(): Flow<List<ChatRoom>> = observe(
        query = { uid -> firestore.collection("chatRooms").whereArrayContains("participantIds", uid) },
        decode = { decodeRoom(it) },
    )

    override fun observeMessages(roomId: String): Flow<List<Message>> = observe(
        query = {
            checkRoomId(roomId)
            firestore.collection("chatRooms").document(roomId).collection("messages")
                .orderBy("createdAt").orderBy(FieldPath.documentId()).limitToLast(50)
        },
        decode = { decodeMessage(it) },
        includePending = true,
    )

    private fun <T> observe(query: (String) -> Query, decode: (DocumentSnapshot) -> T,
        includePending: Boolean = false): Flow<List<T>> = callbackFlow<Pair<String, List<T>>> {
        val uid = requireUser()
        val listener = query(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                sessionUid() != uid -> close(ChatFailure(ChatFailureReason.UNAUTHENTICATED))
                error != null -> close(chatFailure(error))
                snapshot != null && !snapshot.metadata.isFromCache
                    && (includePending || !snapshot.metadata.hasPendingWrites()) -> try {
                    trySend(uid to snapshot.documents.map(decode))
                } catch (failure: Exception) {
                    close(chatFailure(failure))
                }
            }
        }
        val authListener = FirebaseAuth.AuthStateListener {
            if (sessionUid() != uid) close(ChatFailure(ChatFailureReason.UNAUTHENTICATED))
        }
        auth.addAuthStateListener(authListener)
        awaitClose {
            listener.remove()
            auth.removeAuthStateListener(authListener)
        }
    }.buffer(Channel.CONFLATED).map { (uid, items) ->
        // 버퍼에 들어간 직후 계정이 바뀌어도 이전 계정 데이터를 전달하지 않는다.
        requireSameUser(uid)
        items
    }

    override suspend fun getOrCreateRoom(clubId: String): String = operation {
        checkId(clubId, reservedDelimiter = true)
        val uid = requireUser()
        checkId(uid, reservedDelimiter = true)
        val roomId = "${clubId}__$uid"
        val ref = firestore.collection("chatRooms").document(roomId)
        firestore.runTransaction { tx ->
            requireSameUser(uid)
            val club = tx.get(firestore.collection("clubs").document(clubId))
            if (!club.exists()) throw ChatFailure(ChatFailureReason.NOT_FOUND)
            val adminId = club.getString("adminId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
            checkId(adminId, reservedDelimiter = true)
            if (adminId == uid) throw ChatFailure(ChatFailureReason.SELF_CHAT)
            val existing = tx.get(ref)
            if (existing.exists()) {
                val room = decodeRoom(existing)
                if (room.clubId != clubId || existing.getString("studentId") != uid || uid !in room.participantIds) {
                    throw ChatFailure(ChatFailureReason.INVALID_DATA)
                }
            } else {
                requireSameUser(uid)
                tx.set(ref, mapOf("clubId" to clubId, "studentId" to uid, "adminId" to adminId,
                    "participantIds" to listOf(uid, adminId), "createdAt" to FieldValue.serverTimestamp()))
            }
            roomId
        }.await().also { requireSameUser(uid) }
    }

    override suspend fun sendMessage(roomId: String, text: String, messageId: String, expectedUserId: String?) = operation {
        checkRoomId(roomId)
        checkId(messageId)
        if (text.isBlank() || text.length > 2000) throw ChatFailure(ChatFailureReason.INVALID_INPUT)
        val uid = requireUser()
        if (expectedUserId != null && expectedUserId != uid) throw ChatFailure(ChatFailureReason.UNAUTHENTICATED)
        val room = firestore.collection("chatRooms").document(roomId)
        val ref = room.collection("messages").document(messageId)
        firestore.runTransaction { tx ->
            requireSameUser(uid)
            val parent = tx.get(room)
            if (!parent.exists()) throw ChatFailure(ChatFailureReason.NOT_FOUND)
            if (uid !in decodeRoom(parent).participantIds) throw ChatFailure(ChatFailureReason.PERMISSION_DENIED)
            val existing = tx.get(ref)
            if (existing.exists()) {
                val message = decodeMessage(existing)
                if (message.senderId != uid || message.text != text) throw ChatFailure(ChatFailureReason.CONFLICT)
            } else {
                requireSameUser(uid)
                tx.set(ref, mapOf("senderId" to uid, "text" to text, "createdAt" to FieldValue.serverTimestamp()))
            }
            Unit
        }.await()
        requireSameUser(uid)
    }

    private fun requireUser(): String = sessionUid() ?: throw ChatFailure(ChatFailureReason.UNAUTHENTICATED)
    private fun requireSameUser(uid: String) {
        if (sessionUid() != uid) throw ChatFailure(ChatFailureReason.UNAUTHENTICATED)
    }

    private fun checkId(id: String, reservedDelimiter: Boolean = false) {
        if (id.isBlank() || id.length > 128 || '/' in id || id == "." || id == ".."
            || id.any { it.isISOControl() } || (reservedDelimiter && "__" in id)) {
            throw ChatFailure(ChatFailureReason.INVALID_INPUT)
        }
    }

    private fun checkRoomId(id: String) {
        val parts = id.split("__")
        if (parts.size != 2) throw ChatFailure(ChatFailureReason.INVALID_INPUT)
        parts.forEach { checkId(it, reservedDelimiter = true) }
    }

    private fun decodeRoom(doc: DocumentSnapshot): ChatRoom = decode {
        val club = doc.getString("clubId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val student = doc.getString("studentId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val admin = doc.getString("adminId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val members = doc.get("participantIds") as? List<*> ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        if (student == admin || members.size != 2 || members.toSet() != setOf(student, admin)
            || doc.id != "${club}__$student" || doc.getTimestamp("createdAt") == null) {
            throw ChatFailure(ChatFailureReason.INVALID_DATA)
        }
        ChatRoom(doc.id, club, listOf(student, admin))
    }

    private fun decodeMessage(doc: DocumentSnapshot): Message = decode {
        val sender = doc.getString("senderId") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val text = doc.getString("text") ?: throw ChatFailure(ChatFailureReason.INVALID_DATA)
        if (sender.isBlank() || text.isBlank() || text.length > 2000) throw ChatFailure(ChatFailureReason.INVALID_DATA)
        val timestamp = doc.getTimestamp("createdAt")
        if (timestamp == null && !doc.metadata.hasPendingWrites()) throw ChatFailure(ChatFailureReason.INVALID_DATA)
        Message(doc.id, sender, text, timestamp?.toDate()?.time)
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (failure: ChatFailure) {
        throw failure
    } catch (failure: Exception) {
        throw ChatFailure(ChatFailureReason.INVALID_DATA, failure)
    }

    private suspend fun <T> operation(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw chatFailure(failure)
    }
}

private fun chatFailure(failure: Exception): ChatFailure {
    generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ChatFailure>().firstOrNull()?.let { return it }
    val code = (failure as? FirebaseFirestoreException)?.code
    val reason = when (code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> ChatFailureReason.PERMISSION_DENIED
        FirebaseFirestoreException.Code.UNAUTHENTICATED -> ChatFailureReason.UNAUTHENTICATED
        FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.ABORTED -> ChatFailureReason.NETWORK
        FirebaseFirestoreException.Code.NOT_FOUND -> ChatFailureReason.NOT_FOUND
        else -> ChatFailureReason.UNKNOWN
    }
    return ChatFailure(reason, failure)
}
