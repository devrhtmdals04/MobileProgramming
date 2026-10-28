package com.mope.app.ui.screens.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.mope.app.data.model.Promotion
import com.mope.app.data.mock.MockData
import com.mope.app.ui.components.*
import com.mope.app.ui.theme.MopeTheme

@Composable
fun HomeScreen(state: LoadState<List<Promotion>>, onRetry: () -> Unit, onPromotion: (String) -> Unit) {
    Page("모프 · 캠퍼스의 새로운 만남") {
        Text("나와 맞는 동아리를 만나 보세요.")
        StateContent(state, onRetry) { posts ->
            posts.forEach { InfoCard(it.title, it.body) { onPromotion(it.id) } }
        }
    }
}
@Preview(showBackground = true)
@Composable
private fun HomePreview() = MopeTheme { HomeScreen(LoadState.Content(MockData.promotions), {}, {}) }
