package com.mope.app.data.mock

import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object MockData {
    val clubs = listOf(
        Club("photo", "찰칵 사진부", "문화", "캠퍼스의 순간을 함께 기록해요.", "매주 목요일 · 초보 환영", "admin-photo"),
        Club("code", "코드메이트", "학술", "작은 프로젝트를 함께 완성하는 개발 동아리", "매주 화요일 · 전 학년 모집", "admin-code"),
        Club("run", "캠퍼스 러너스", "운동", "수업이 끝나면 함께 달려요.", "주 2회 · 상시 모집", "admin-run"),
    )
    val promotions = listOf(
        Promotion("p1", "photo", "함께 담을 새로운 순간", "이번 학기 사진부 신입 부원을 모집합니다."),
        Promotion("p2", "code", "아이디어를 앱으로 만들어요", "함께 배우고 만드는 코드메이트에 오세요."),
    )
}

class MockClubRepository : ClubRepository {
    override fun observeClubs(): Flow<List<Club>> = flowOf(MockData.clubs)
    override fun observePromotions(): Flow<List<Promotion>> = flowOf(MockData.promotions)
}
