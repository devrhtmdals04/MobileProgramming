package com.mope.app.data.repository

import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import kotlinx.coroutines.flow.Flow

interface ClubRepository {
    fun observeClubs(): Flow<List<Club>>
    fun observePromotions(): Flow<List<Promotion>>
}
