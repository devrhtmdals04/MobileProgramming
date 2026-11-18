package com.mope.app.data.remote.club

import com.mope.app.data.model.Club
import com.mope.app.data.model.Promotion
import com.mope.app.data.repository.ClubRepository
import com.mope.app.data.repository.ClubAdminRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LinkedClubRepository(private val directory: ClubRepository, private val admins: ClubAdminRepository) : ClubRepository {
    override fun observeClubs(): Flow<List<Club>> = combine(directory.observeClubs(), admins.observeLinkedClubs()) { official, linked ->
        val changes = linked.associateBy { it.id }
        official.map { source ->
            val managed = changes[source.id]
            if (managed == null) source else source.copy(description = managed.description,
                recruitment = managed.recruitment, adminId = managed.adminId)
        }
    }
    override fun observePromotions(): Flow<List<Promotion>> = observeClubs().map { clubs ->
        clubs.map { Promotion("directory-${it.id}", it.id, it.name, "${it.category} · 숭실대학교 공식 동아리 소개") }
    }
}
