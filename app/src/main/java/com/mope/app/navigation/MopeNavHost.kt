package com.mope.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import com.mope.app.data.repository.ClubRepository
import com.mope.app.ui.screens.auth.*
import com.mope.app.ui.screens.home.*
import com.mope.app.ui.screens.search.*
import com.mope.app.ui.screens.club.*
import com.mope.app.ui.screens.chat.*
import com.mope.app.ui.screens.request.*

@Composable
fun MopeNavHost(repository: ClubRepository) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf("home" to "홈", "search" to "탐색", "chats" to "대화", "requests" to "개설", "profile" to "내 정보")
    Scaffold(
        topBar = { if (route != "auth") Surface { Text("MOPE · Mock 미리보기", Modifier.statusBarsPadding().padding(horizontal = 16.dp)) } },
        bottomBar = {
            if (tabs.any { it.first == route }) NavigationBar {
                tabs.forEach { (target, label) ->
                    NavigationBarItem(selected = route == target, onClick = {
                        nav.navigate(target) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Text(label.take(1)) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(navController = nav, startDestination = "auth") {
                composable("auth") { AuthScreen { nav.navigate("home") { popUpTo("auth") { inclusive = true } } } }
                composable("home") {
                    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(repository) } })
                    val state by vm.state.collectAsStateWithLifecycle()
                    HomeScreen(state, vm::reload) { nav.navigate("promotion/${Uri.encode(it)}") }
                }
                composable("search") {
                    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(repository) } })
                    val state by vm.state.collectAsStateWithLifecycle()
                    val query by vm.query.collectAsStateWithLifecycle()
                    val category by vm.category.collectAsStateWithLifecycle()
                    SearchScreen(state, query, category, vm::setQuery, vm::setCategory, vm::reload) { nav.navigate("club/${Uri.encode(it)}") }
                }
                listOf("club", "promotion").forEach { kind ->
                    composable("$kind/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id").orEmpty()
                        val vm: ClubViewModel = viewModel(factory = viewModelFactory { initializer { ClubViewModel(repository, id, kind == "promotion") } })
                        val state by vm.state.collectAsStateWithLifecycle()
                        ClubScreen(state, { nav.popBackStack() }, { nav.navigate("chat/${Uri.encode(it)}") }, { nav.navigate("apply/${Uri.encode(it)}") })
                    }
                }
                composable("chats") { ChatListScreen() }
                composable("chat/{clubId}") { ChatScreen(it.arguments?.getString("clubId").orEmpty()) { nav.popBackStack() } }
                composable("requests") { RequestScreen() }
                composable("apply/{clubId}") { RequestScreen(it.arguments?.getString("clubId").orEmpty()) { nav.popBackStack() } }
                composable("profile") { ProfileScreen { nav.navigate("auth") { popUpTo(nav.graph.id) { inclusive = true } } } }
            }
        }
    }
}
