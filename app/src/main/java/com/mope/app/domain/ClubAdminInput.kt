package com.mope.app.domain

import java.net.URI
import com.mope.app.data.repository.RequestFailure
import com.mope.app.data.repository.RequestFailureReason

object ClubAdminInput {
    fun sourceUrl(clubId: String): String {
        valid(clubId.matches(Regex("ssu-[1-9][0-9]{0,8}")))
        return "https://clubunion.ssu.ac.kr/clubs/${clubId.removePrefix("ssu-")}"
    }
    fun reason(value: String): String = value.trim().also { valid(it.length in 1..1000) }
    fun channel(value: String): String {
        val text = value.trim()
        val uri = try { URI(text) } catch (_: Exception) { invalid() }
        valid(text.length in 1..500 && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        return text
    }
    fun description(value: String): String = value.trim().also { valid(it.length in 1..2000) }
    fun recruitment(value: String): String = value.trim().also { valid(it.length <= 1000) }
    private fun valid(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw RequestFailure(RequestFailureReason.INVALID_INPUT)
}
