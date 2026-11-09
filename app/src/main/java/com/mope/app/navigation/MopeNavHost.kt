package com.mope.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mope.app.data.repository.AuthRepository
import com.mope.app.data.repository.ClubRepository
import com.mope.app.ui.screens.auth.AuthScreen
import com.mope.app.ui.screens.auth.AuthSessionUiState
import com.mope.app.ui.screens.auth.AuthViewModel
import com.mope.app.ui.screens.auth.ProfileScreen
import com.mope.app.ui.screens.chat.ChatListScreen
import com.mope.app.ui.screens.chat.ChatScreen
import com.mope.app.ui.screens.club.ClubScreen
import com.mope.app.ui.screens.club.ClubViewModel
import com.mope.app.ui.screens.home.HomeScreen
import com.mope.app.ui.screens.home.HomeViewModel
import com.mope.app.ui.screens.request.RequestScreen
import com.mope.app.ui.screens.search.SearchScreen
import com.mope.app.ui.screens.search.SearchViewModel

private val mainTabs = listOf(
    "home" to "홈",
    "search" to "탐색",
    "chats" to "대화",
    "requests" to "개설",
    "profile" to "내 정보",
)

@Composable
fun MopeNavHost(
    repository: ClubRepository,
    authRepository: AuthRepository? = null,
) {
    if (authRepository != null) {
        AuthenticatedMopeNavHost(repository, authRepository)
    } else {
        MockMopeNavHost(repository)
    }
}

@Composable
private fun AuthenticatedMopeNavHost(
    repository: ClubRepository,
    authRepository: AuthRepository,
) {
    val nav = rememberNavController()
    val authViewModel: AuthViewModel = viewModel(
        factory = viewModelFactory { initializer { AuthViewModel(authRepository) } },
    )
    val authSession by authViewModel.session.collectAsStateWithLifecycle()
    var mockExploring by rememberSaveable { mutableStateOf(false) }
    val route = currentRoute(nav)

    LaunchedEffect(authSession, mockExploring, route) {
        when {
            authSession is AuthSessionUiState.SignedIn && route == "auth" -> {
                mockExploring = false
                nav.navigate("home") {
                    popUpTo("auth") { inclusive = true }
                    launchSingleTop = true
                }
            }
            authSession is AuthSessionUiState.SignedOut &&
                !mockExploring &&
                route != null &&
                route != "auth" -> {
                nav.navigate("auth") {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    MopeScaffold(
        route = route,
        title = if (authSession is AuthSessionUiState.SignedIn) "MOPE" else "MOPE · Mock 미리보기",
        nav = nav,
    ) {
        NavHost(navController = nav, startDestination = "auth") {
            composable("auth") {
                val form by authViewModel.form.collectAsStateWithLifecycle()
                AuthScreen(
                    form = form,
                    session = authSession,
                    onModeChange = authViewModel::setMode,
                    onEmailChange = authViewModel::onEmailChange,
                    onPasswordChange = authViewModel::onPasswordChange,
                    onDisplayNameChange = authViewModel::onDisplayNameChange,
                    onSubmit = authViewModel::submit,
                    onRetrySession = authViewModel::retrySession,
                    onExplore = {
                        mockExploring = true
                        nav.navigate("home") { popUpTo("auth") { inclusive = true } }
                    },
                )
            }
            clubRoutes(nav, repository)
            composable("profile") {
                ProfileScreen(
                    user = (authSession as? AuthSessionUiState.SignedIn)?.user,
                    onSignOut = {
                        mockExploring = false
                        authViewModel.signOut()
                    },
                    onExitMock = if (authSession !is AuthSessionUiState.SignedIn) {
                        {
                            mockExploring = false
                            nav.navigate("auth") {
                                popUpTo(nav.graph.id) { inclusive = true }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun MockMopeNavHost(repository: ClubRepository) {
    val nav = rememberNavController()
    val route = currentRoute(nav)
    MopeScaffold(route = route, title = "MOPE · Mock 미리보기", nav = nav) {
        NavHost(navController = nav, startDestination = "auth") {
            composable("auth") {
                AuthScreen(
                    onExplore = {
                        nav.navigate("home") { popUpTo("auth") { inclusive = true } }
                    },
                )
            }
            clubRoutes(nav, repository)
            composable("profile") {
                ProfileScreen(
                    user = null,
                    onSignOut = {
                        nav.navigate("auth") {
                            popUpTo(nav.graph.id) { inclusive = true }
                        }
                    },
                    onExitMock = {
                        nav.navigate("auth") {
                            popUpTo(nav.graph.id) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun currentRoute(nav: NavHostController): String? {
    val entry by nav.currentBackStackEntryAsState()
    return entry?.destination?.route
}

@Composable
private fun MopeScaffold(
    route: String?,
    title: String,
    nav: NavHostController,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            if (route != "auth") {
                Surface {
                    Text(title, Modifier.statusBarsPadding().padding(horizontal = 16.dp))
                }
            }
        },
        bottomBar = {
            if (mainTabs.any { it.first == route }) {
                NavigationBar {
                    mainTabs.forEach { (target, label) ->
                        NavigationBarItem(
                            selected = route == target,
                            onClick = {
                                if (route != target) {
                                    nav.navigate(target) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Text(label.take(1)) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding), content = { content() })
    }
}

private fun androidx.navigation.NavGraphBuilder.clubRoutes(
    nav: NavHostController,
    repository: ClubRepository,
) {
    composable("home") {
        val vm: HomeViewModel = viewModel(
            factory = viewModelFactory { initializer { HomeViewModel(repository) } },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        HomeScreen(state, vm::reload) { nav.navigate("promotion/${Uri.encode(it)}") }
    }
    composable("search") {
        val vm: SearchViewModel = viewModel(
            factory = viewModelFactory { initializer { SearchViewModel(repository) } },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        val query by vm.query.collectAsStateWithLifecycle()
        val category by vm.category.collectAsStateWithLifecycle()
        SearchScreen(state, query, category, vm::setQuery, vm::setCategory, vm::reload) {
            nav.navigate("club/${Uri.encode(it)}")
        }
    }
    listOf("club", "promotion").forEach { kind ->
        composable("$kind/{id}") { backStack ->
            val id = backStack.arguments?.getString("id").orEmpty()
            val vm: ClubViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ClubViewModel(repository, id, kind == "promotion") }
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            ClubScreen(
                state,
                { nav.popBackStack() },
                { nav.navigate("chat/${Uri.encode(it)}") },
                { nav.navigate("apply/${Uri.encode(it)}") },
            )
        }
    }
    composable("chats") { ChatListScreen() }
    composable("chat/{clubId}") {
        ChatScreen(it.arguments?.getString("clubId").orEmpty()) { nav.popBackStack() }
    }
    composable("requests") { RequestScreen() }
    composable("apply/{clubId}") {
        RequestScreen(it.arguments?.getString("clubId").orEmpty()) { nav.popBackStack() }
    }
}
