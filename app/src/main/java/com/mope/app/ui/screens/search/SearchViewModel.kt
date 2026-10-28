package com.mope.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.Club
import com.mope.app.data.repository.ClubRepository
import com.mope.app.domain.filterClubs
import com.mope.app.ui.components.LoadState
import kotlinx.coroutines.flow.*

class SearchViewModel(private val repository: ClubRepository) : ViewModel() {
    private val mutableQuery = MutableStateFlow("")
    val query = mutableQuery.asStateFlow()
    private val mutableCategory = MutableStateFlow<String?>(null)
    val category = mutableCategory.asStateFlow()
    private val refresh = MutableStateFlow(0)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val clubs = refresh.flatMapLatest {
        repository.observeClubs().map<List<Club>, LoadState<List<Club>>> { LoadState.Content(it) }
            .onStart { emit(LoadState.Loading) }
            .catch { emit(LoadState.Error("동아리를 불러오지 못했어요.")) }
    }
    val state = combine(clubs, query, category) { source, text, selected ->
        if (source is LoadState.Content) {
            val result = filterClubs(source.value, text, selected)
            if (result.isEmpty()) LoadState.Empty else LoadState.Content(result)
        } else source
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)
    fun setQuery(value: String) { mutableQuery.value = value }
    fun setCategory(value: String?) { mutableCategory.value = value }
    fun reload() { refresh.value += 1 }
}
