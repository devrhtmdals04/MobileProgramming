package com.mope.app.data.remote.club

import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import com.mope.app.data.repository.ClubAdminRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class LinkedClubRepository(private val directory: ClubRepository, private val admins: ClubAdminRepository,
    private val promotionFeed: () -> Flow<List<Promotion>>,
    private val includeLocalClubs: Boolean = false) : ClubRepository {
    override fun observeClubs(): Flow<List<Club>> = combine(directory.observeClubs(), admins.observeLinkedClubs()) { official, linked ->
        val changes = linked.associateBy { it.id }
        official.map { source ->
            val managed = changes[source.id]
            if (managed == null) source else source.copy(description = managed.description,
                recruitment = managed.recruitment, adminId = managed.adminId)
        } + if (includeLocalClubs) linked.filter { club -> official.none { it.id == club.id } } else emptyList()
    }
    override fun observePromotions(): Flow<List<Promotion>> =
        combine(promotionFeed(), directory.observePromotions()) { posts, directoryCards ->
            posts + directoryCards
        }
}
