package com.mope.app.data.repository

import com.mope.app.data.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Mock 탐색은 실제 인증으로 취급하지 않는다.
 *
 * currentUser의 null은 인증 복원 완료 후 로그아웃 상태를 뜻한다. 복원 중에는
 * 최초 값을 내보내지 않는다. 인증된 계정의 프로필 읽기 실패는 null 대신
 * AuthFailure(PROFILE_UNAVAILABLE)를 전달한다. 재구독으로 복원을 재시도한다.
 *
 * 구현체는 AuthInput으로 입력을 검증하고 SDK 오류를 AuthFailure로 변환한다.
 * CancellationException은 변환하지 않고 전파한다. signUp은 인증 계정과
 * users 프로필 저장이 모두 완료되어야 반환한다. 프로필 실패 시 성공 사용자로
 * 발행하지 않으며, 다음 signIn에서 누락된 본인 프로필 생성을 재시도한다.
 * role은 클라이언트 입력으로 받지 않고 최초 STUDENT로 생성한다.
 * signOut 완료 후에는 currentUser에 null을 발행한다.
 */
interface AuthRepository {
    val currentUser: Flow<User?>
    suspend fun signIn(email: String, password: String)
    suspend fun signUp(email: String, password: String, displayName: String)
    suspend fun signOut()
    /**
     * 재설정 메일 요청. 가입 여부는 결과로 구분하지 않으며 현재 세션을 변경하지 않는다.
     * 정상 반환은 요청 접수이며 실제 메일 수신/비밀번호 변경 완료를 뜻하지 않는다.
     */
    suspend fun requestPasswordReset(email: String)
}
