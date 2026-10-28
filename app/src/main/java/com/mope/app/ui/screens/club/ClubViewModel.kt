package com.mope.app.ui.screens.club

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import com.mope.app.ui.components.LoadState
import kotlinx.coroutines.flow.*

data class ClubDetail(val club: Club, val promotion: Promotion? = null)
class ClubViewModel(repository: ClubRepository, id: String, isPromotion: Boolean) : ViewModel() {
    val state = combine(repository.observeClubs(), repository.observePromotions()) { clubs, posts ->
        val post = if (isPromotion) posts.find { it.id == id } else null
        val club = clubs.find { it.id == if (isPromotion) post?.clubId else id }
        if (club == null) LoadState.Empty else LoadState.Content(ClubDetail(club, post))
    }.catch { emit(LoadState.Error("상세 정보를 불러오지 못했어요. 뒤로 간 뒤 다시 열어 주세요.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
}
