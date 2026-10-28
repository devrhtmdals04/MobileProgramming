package com.mope.app.ui

import com.mope.app.data.mock.MockData
import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import com.mope.app.ui.components.LoadState
import com.mope.app.ui.screens.home.HomeViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private class Repository : ClubRepository {
        var fail = false
        var posts = MockData.promotions
        override fun observeClubs(): Flow<List<Club>> = flowOf(emptyList())
        override fun observePromotions(): Flow<List<Promotion>> = flow {
            if (fail) error("offline")
            emit(posts)
        }
    }
    @Test fun startsLoadingThenShowsContent() = runTest {
        val vm = HomeViewModel(Repository())
        assertEquals(LoadState.Loading, vm.state.value)
        runCurrent()
        assertEquals(LoadState.Content(MockData.promotions), vm.state.value)
    }
    @Test fun emptyResponseShowsEmptyState() = runTest {
        val repository = Repository().apply { posts = emptyList() }
        val vm = HomeViewModel(repository)
        runCurrent()
        assertEquals(LoadState.Empty, vm.state.value)
    }
    @Test fun retriesAfterRepositoryFailure() = runTest {
        val repository = Repository().apply { fail = true }
        val vm = HomeViewModel(repository)
        runCurrent()
        assertTrue(vm.state.value is LoadState.Error)
        repository.fail = false
        vm.reload()
        assertEquals(LoadState.Loading, vm.state.value)
        runCurrent()
        assertEquals(LoadState.Content(MockData.promotions), vm.state.value)
    }
}
