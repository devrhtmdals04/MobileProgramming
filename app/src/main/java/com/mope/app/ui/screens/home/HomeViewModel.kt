package com.mope.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import com.mope.app.ui.components.LoadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ClubRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<LoadState<List<Promotion>>>(LoadState.Loading)
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    init { reload() }
    fun reload() {
        loadJob?.cancel()
        mutableState.value = LoadState.Loading
        loadJob = viewModelScope.launch {
            repository.observePromotions().catch {
                mutableState.value = LoadState.Error("홍보글을 불러오지 못했어요.")
            }.collect { mutableState.value = if (it.isEmpty()) LoadState.Empty else LoadState.Content(it) }
        }
    }
}
