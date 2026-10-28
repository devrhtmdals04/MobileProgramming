package com.mope.app.domain

import com.mope.app.data.model.Club

fun filterClubs(clubs: List<Club>, query: String, category: String?): List<Club> {
    val keyword = query.trim()
    return clubs.filter { club ->
        (category == null || club.category == category) &&
            (club.name.contains(keyword, ignoreCase = true) ||
                club.description.contains(keyword, ignoreCase = true))
    }
}
