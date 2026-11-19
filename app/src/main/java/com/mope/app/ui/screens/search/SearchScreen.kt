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
fun SearchScreen(state: LoadState<List<Club>>, query: String, category: String?, onQuery: (String) -> Unit, onCategory: (String?) -> Unit, onRetry: () -> Unit, onClub: (String) -> Unit, categories: List<String> = listOf("문화", "학술", "운동")) {
    Page("동아리 탐색") {
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("이름 또는 키워드") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf<String?>(null) + (categories + listOfNotNull(category)).distinct()).forEach { item ->
                FilterChip(selected = category == item, onClick = { onCategory(item) }, label = { Text(item ?: "전체") })
            }
        }
        if (state == LoadState.Empty && (query.isNotBlank() || category != null)) {
            Text("검색 결과가 없어요. 다른 키워드나 분류로 찾아보세요.")
            TextButton(onClick = { onQuery(""); onCategory(null) }) { Text("검색 조건 초기화") }
        } else StateContent(state, onRetry) { clubs -> clubs.forEach { InfoCard(it.name, "${it.category} · ${it.description}") { onClub(it.id) } } }
    }
}
@Preview(showBackground = true)
@Composable
private fun SearchPreview() = MopeTheme { SearchScreen(LoadState.Content(MockData.clubs), "", null, {}, {}, {}, {}) }
