package com.mope.app.ui

import com.mope.app.data.mock.MockClubRepository
import com.mope.app.ui.components.LoadState
import com.mope.app.ui.screens.search.SearchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun updatesResultsWhenQueryAndCategoryChange() = runTest {
        val vm = SearchViewModel(MockClubRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        vm.setQuery("사진")
        runCurrent()
        val content = vm.state.value as LoadState.Content
        assertEquals(listOf("photo"), content.value.map { it.id })
        vm.setCategory("운동")
        runCurrent()
        assertEquals(LoadState.Empty, vm.state.value)
        vm.setQuery("")
        runCurrent()
        assertEquals(listOf("run"), (vm.state.value as LoadState.Content).value.map { it.id })
    }
}
