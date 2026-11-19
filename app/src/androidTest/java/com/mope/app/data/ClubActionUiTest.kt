package com.mope.app.data

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mope.app.BuildConfig
import com.mope.app.MainActivity
import com.mope.app.MopeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class ClubActionUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun ownerPublishesThenMemberReadsAppliesAndFindsHistory() {
        assumeTrue(BuildConfig.BACKEND == "emulator")
        val app = ui.activity.application as MopeApplication
        val auth = requireNotNull(app.authRepository)
        val name = "화면검증 동아리"
        val id = "ssu-${System.currentTimeMillis() % 100000000 + 1}"
        try {
            runBlocking {
                auth.signOut()
                auth.createPhoneTestUser("관리자")
                seedClub(id, name, auth.currentUser.first { it != null }!!.id)
            }
            tap("탐색")
            tap(name, scroll = true)
            tap("홍보글 작성", scroll = true)
            awaitText("홍보글 등록")
            ui.onNode(hasSetTextAction() and hasText("홍보글 제목 (최대 100자)")).performTextReplacement("신입 모집")
            ui.onNode(hasSetTextAction() and hasText("홍보 내용")).performTextReplacement("목요일에 만나요")
            tap("홍보글 등록", scroll = true)
            awaitText("홍보글을 등록했어요.")
            runBlocking { auth.signOut() }
            awaitText("전화번호로 시작하기")
            runBlocking { auth.createPhoneTestUser("회원") }
            tap("신입 모집", scroll = true)
            awaitText("목요일에 만나요")
            tap("가입 신청", scroll = true)
            tap("가입 신청 제출", scroll = true)
            awaitText("필수 입력란과 글자 수를 확인해 주세요.")
            ui.onNode(hasSetTextAction() and hasText("자기소개")).performTextReplacement("함께하고 싶어요")
            tap("가입 신청 제출", scroll = true)
            awaitText("가입 신청이 접수됐어요.")
            tap("뒤로")
            tap("뒤로")
            tap("내 정보")
            tap("내 가입 신청", scroll = true)
            awaitText("신청 접수 · 함께하고 싶어요")
            tap("뒤로")
            tap("탐색")
            tap(name, scroll = true)
            tap("관리자에게 문의", scroll = true)
            awaitText("메시지")
            ui.onNode(hasSetTextAction() and hasText("메시지")).performTextReplacement("활동 시간을 알려주세요")
            tap("보내기")
            awaitText("나 · 활동 시간을 알려주세요")
            tap("뒤로")
            tap("뒤로")
            tap("대화")
            tap(name, scroll = true)
            awaitText("나 · 활동 시간을 알려주세요")
        } finally { runBlocking { auth.signOut() } }
    }

    private fun awaitText(label: String) {
        ui.waitUntil(15000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(label: String, scroll: Boolean = false) {
        awaitText(label)
        val node = ui.onAllNodesWithText(label).onLast()
        if (scroll) node.performScrollTo()
        node.performClick()
    }

    private fun seedClub(id: String, name: String, owner: String) {
        val fields = JSONObject()
        mapOf("name" to name, "adminId" to owner, "description" to "소개", "recruitment" to "모집",
            "category" to "학술", "sourceUrl" to "https://clubunion.ssu.ac.kr/clubs/${id.removePrefix("ssu-")}")
            .forEach { (key, value) -> fields.put(key, JSONObject().put("stringValue", value)) }
        val conn = URL("http://10.0.2.2:8080/v1/projects/demo-mope/databases/(default)/documents/clubs/$id")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Authorization", "Bearer owner")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("fields", fields).toString().toByteArray()) }
            check(conn.responseCode == 200)
        } finally { conn.disconnect() }
    }
}
