package com.mope.app.data.repository

import com.mope.app.data.model.ClubRequest
import com.mope.app.data.model.RequestStatus
import kotlinx.coroutines.flow.Flow

/** 개설 요청 전용 계약. 수요조사·가입 신청과 별개다. */
interface ClubRequestRepository {
    /** 제출 전에 생성·보관하고 같은 입력의 재시도에 재사용한다. 로그인 필요. */
    fun newRequestId(): String
    suspend fun submitClubRequest(name: String, purpose: String, requestId: String = newRequestId()): String
    fun observeMyRequests(): Flow<List<ClubRequest>>
    fun observePendingRequests(): Flow<List<ClubRequest>>
    suspend fun reviewRequest(id: String, status: RequestStatus)
}

enum class RequestFailureReason {
    INVALID_INPUT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND, CONFLICT, NETWORK, INVALID_DATA, UNKNOWN,
}

class RequestFailure(val reason: RequestFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)
