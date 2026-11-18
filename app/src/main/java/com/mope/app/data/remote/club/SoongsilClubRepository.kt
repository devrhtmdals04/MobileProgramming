package com.mope.app.data.remote.club

import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public directory used by SSU Club Union's website. No university credentials or personal contacts. */
class SoongsilClubRepository : ClubRepository {
    override fun observeClubs(): Flow<List<Club>> = flow {
        emit(withContext(Dispatchers.IO) {
            val clubs = mutableListOf<Club>()
            var page = 0
            do {
                check(page < 10) { "Unexpected directory page count" }
                val connection = URL("https://clubunion.ssu.ac.kr/api/club/list?page=$page&size=100")
                    .openConnection() as HttpURLConnection
                val result = try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", "application/json")
                    check(connection.responseCode == 200) { "Could not load the official directory" }
                    val body = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    check(body.optBoolean("isSuccess")) { "Official directory request failed" }
                    body.getJSONObject("result")
                } finally { connection.disconnect() }
                val content = result.getJSONArray("content")
                for (index in 0 until content.length()) {
                    val item = content.getJSONObject(index)
                    val sourceId = item.getLong("id")
                    val name = item.getString("clubName").trim()
                    check(sourceId > 0 && name.isNotBlank())
                    val category = categoryLabel(item.getString("category"))
                    // Only factual name/category/official link are imported; no representative names or phone numbers.
                    clubs += Club("ssu-$sourceId", name, category,
                        "숭실대학교 동아리연합회에 공개된 $category 동아리입니다.",
                        "모집 여부와 활동 안내는 공식 소개에서 확인해 주세요.", "",
                        sourceUrl = "https://clubunion.ssu.ac.kr/clubs/$sourceId")
                }
                page++
            } while (result.optBoolean("hasNext"))
            check(clubs.map { it.id }.distinct().size == clubs.size)
            clubs
        })
    }

    /** Directory cards on home are identified as official introductions, not invented recruitment posts. */
    override fun observePromotions(): Flow<List<Promotion>> = observeClubs().map { clubs ->
        clubs.map { Promotion("directory-${it.id}", it.id, it.name, "${it.category} · 숭실대학교 공식 동아리 소개") }
    }
}

internal fun categoryLabel(value: String): String = when (value) {
    "GENERAL_EDUCATION" -> "교양"
    "SOLIDARITY_BUSINESS" -> "연대사업"
    "PERFORMING_ARTS" -> "연행예술"
    "RELIGION" -> "종교"
    "CREATIVE_EXHIBITION" -> "창작전시"
    "SPORTS" -> "체육"
    "ACADEMIC" -> "학술"
    else -> "기타"
}
