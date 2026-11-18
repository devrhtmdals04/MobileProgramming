package com.mope.app.data

import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.PhoneCodeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ThreadLocalRandom

internal fun newTestPhone(): String = "+1650" + ThreadLocalRandom.current().nextInt(1000000, 9999999)

internal data class TestPhoneCode(val id: String, val code: String)

internal suspend fun AuthRepository.testPhoneCode(phone: String): TestPhoneCode {
    val sent = requestPhoneCode(phone, null).first { it is PhoneCodeEvent.CodeSent } as PhoneCodeEvent.CodeSent
    val code = withContext(Dispatchers.IO) {
        val connection = URL("http://10.0.2.2:9099/emulator/v1/projects/demo-mope/verificationCodes").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            check(connection.responseCode == 200)
            val codes = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("verificationCodes")
            (0 until codes.length()).map { codes.getJSONObject(it) }.last { it.getString("sessionInfo") == sent.verificationId }.getString("code")
        } finally { connection.disconnect() }
    }
    return TestPhoneCode(sent.verificationId, code)
}

internal suspend fun AuthRepository.createPhoneTestUser(name: String) {
    val challenge = testPhoneCode(newTestPhone())
    verifyPhoneCode(challenge.id, challenge.code)
    completeProfile(name)
}
