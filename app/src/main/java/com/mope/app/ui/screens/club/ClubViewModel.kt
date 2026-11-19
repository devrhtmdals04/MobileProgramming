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
    private val refresh = MutableStateFlow(0)
    fun retry() { refresh.value++ }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state = refresh.flatMapLatest {
        val posts = if (isPromotion) repository.observePromotions() else flowOf(emptyList())
        combine(repository.observeClubs(), posts) { clubs, promotions ->
            val post = promotions.find { it.id == id }
            val clubId = if (isPromotion) post?.clubId else id
            val club = clubs.find { it.id == clubId }
            if (club == null) LoadState.Empty else LoadState.Content(ClubDetail(club, post))
        }.onStart { emit(LoadState.Loading) }
            .catch { emit(LoadState.Error("상세 정보를 불러오지 못했어요. 연결을 확인해 주세요.")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
}
