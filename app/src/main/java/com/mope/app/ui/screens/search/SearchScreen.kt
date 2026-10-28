package com.mope.app.ui.screens.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mope.app.data.model.Club
import com.mope.app.data.mock.MockData
import com.mope.app.ui.components.*
import com.mope.app.ui.theme.MopeTheme

@Composable
fun SearchScreen(state: LoadState<List<Club>>, query: String, category: String?, onQuery: (String) -> Unit, onCategory: (String?) -> Unit, onRetry: () -> Unit, onClub: (String) -> Unit) {
    Page("동아리 탐색") {
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("이름 또는 키워드") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, "문화", "학술", "운동").forEach { item ->
                FilterChip(selected = category == item, onClick = { onCategory(item) }, label = { Text(item ?: "전체") })
            }
        }
        StateContent(state, onRetry) { clubs -> clubs.forEach { InfoCard(it.name, "${it.category} · ${it.description}") { onClub(it.id) } } }
    }
}
@Preview(showBackground = true)
@Composable
private fun SearchPreview() = MopeTheme { SearchScreen(LoadState.Content(MockData.clubs), "", null, {}, {}, {}, {}) }
