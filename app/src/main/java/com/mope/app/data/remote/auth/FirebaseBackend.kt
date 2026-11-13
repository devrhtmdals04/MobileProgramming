package com.mope.app.data.remote.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.mope.app.BuildConfig
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ClubRequestRepository
import com.mope.app.data.remote.request.FirestoreClubRequestRepository
import com.mope.app.data.repository.ChatRepository
import com.mope.app.data.remote.chat.FirestoreChatRepository

data class FirebaseRepositories(val auth: AuthRepository, val chat: ChatRepository, val clubRequests: ClubRequestRepository)

object FirebaseBackend {
    fun createRepositories(context: Context): FirebaseRepositories {
        check(BuildConfig.BACKEND != "mock") { "Mock mode has no real authentication" }
        val emulator = BuildConfig.BACKEND == "emulator"
        check(!emulator || BuildConfig.DEBUG) { "Emulator backend is debug-only" }
        val app = if (emulator) {
            FirebaseApp.getApps(context).find { it.name == "mope-emulator" }
                ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                    .setProjectId("demo-mope")
                    .setApplicationId("1:1234567890:android:mope-emulator")
                    .setApiKey("demo-mope-api-key")
                    .build(), "mope-emulator")
        } else {
            FirebaseApp.initializeApp(context) ?: error("Missing Firebase configuration")
        }
        val auth = FirebaseAuth.getInstance(app)
        val firestore = FirebaseFirestore.getInstance(app)
        if (emulator) {
            auth.useEmulator("10.0.2.2", 9099)
            firestore.useEmulator("10.0.2.2", 8080)
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        }
        val logoutPreferences = context.getSharedPreferences("auth-logout-${app.name}", Context.MODE_PRIVATE)
        val authRepository = FirebaseAuthRepository(auth, firestore, logoutPreferences)
        val sessionUid = { if (logoutPreferences.getBoolean("signedOut", false)) null else auth.currentUser?.uid }
        return FirebaseRepositories(authRepository, FirestoreChatRepository(auth, firestore, sessionUid),
            FirestoreClubRequestRepository(auth, firestore, sessionUid))
    }
}
