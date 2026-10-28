package com.mope.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
fun <T> StateContent(state: LoadState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> CircularProgressIndicator()
        LoadState.Empty -> Text("아직 표시할 내용이 없어요.")
        is LoadState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("다시 시도") }
        }
        is LoadState.Content -> content(state.value)
    }
}

@Composable
fun InfoCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
