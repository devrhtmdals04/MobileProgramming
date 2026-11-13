import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, setDoc, getDoc, getDocs, updateDoc, deleteDoc, query, where, orderBy,
  serverTimestamp, Timestamp, deleteField } from 'firebase/firestore';
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-mope',
  firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    for (const uid of ['alice', 'bob', 'admin']) await setDoc(doc(context.firestore(), 'users', uid),
      { displayName: uid, role: uid === 'admin' ? 'club_admin' : 'student', createdAt: serverTimestamp() });
  });
});
after(async () => { await env?.cleanup(); });
const db = (uid, claims = {}) => uid ? env.authenticatedContext(uid, claims).firestore() : env.unauthenticatedContext().firestore();
const ref = (uid, claims) => doc(db(uid, claims), 'clubRequests/alice__request');
const pending = (extra = {}) => ({ applicantId: 'alice', name: '사진부', purpose: '함께 기록해요\n초보 환영', status: 'pending',
  createdAt: serverTimestamp(), reviewedBy: null, reviewedAt: null, ...extra });
const review = (status = 'approved', extra = {}) => ({ status, reviewedBy: 'operator', reviewedAt: serverTimestamp(), ...extra });
const seed = () => setDoc(ref('alice'), pending());

test('본인 생성·단건·조건부 목록 및 운영자 대기 조회 허용', async () => {
  await assertSucceeds(getDoc(ref('alice')));
  await assertSucceeds(seed());
  await assertSucceeds(getDoc(ref('alice')));
  await assertSucceeds(getDocs(query(collection(db('alice'), 'clubRequests'), where('applicantId', '==', 'alice'), orderBy('createdAt', 'desc'))));
  await assertSucceeds(getDocs(query(collection(db('operator', { operator: true }), 'clubRequests'), where('status', '==', 'pending'), orderBy('createdAt'))));
});
test('비로그인·타인 단건/목록·프로필 없는 제출·작성자/ID 위조 거절', async () => {
  await seed();
  for (const uid of [undefined, 'bob', 'admin']) {
    await assertFails(getDoc(ref(uid)));
    await assertFails(getDocs(collection(db(uid), 'clubRequests')));
  }
  await assertFails(setDoc(doc(db('ghost'), 'clubRequests/ghost__new'), pending({ applicantId: 'ghost' })));
  await assertFails(setDoc(doc(db('bob'), 'clubRequests/alice__forged'), pending()));
  await assertFails(setDoc(doc(db('alice'), 'clubRequests/alice__new'), pending({ applicantId: 'bob' })));
  await assertFails(getDocs(collection(db('alice'), 'clubRequests')));
  await assertFails(setDoc(doc(db('alice'), 'clubRequests/arbitrary'), pending()));
});
test('입력·필드·상태·시각 경계 검증', async () => {
  for (const extra of [{ name: '' }, { name: ' \n\t' }, { name: '가'.repeat(51) }, { name: 1 },
    { purpose: '' }, { purpose: ' \n' }, { purpose: '가'.repeat(2001) }, { purpose: null },
    { status: 'approved' }, { reviewedBy: 'operator' }, { reviewedAt: Timestamp.fromMillis(0) },
    { createdAt: Timestamp.fromMillis(0) }, { operator: true }]) await assertFails(setDoc(ref('alice'), pending(extra)));
  const missing = pending(); delete missing.reviewedAt;
  await assertFails(setDoc(ref('alice'), missing));
  await assertSucceeds(setDoc(ref('alice'), pending({ name: '가'.repeat(50), purpose: '나'.repeat(2000) })));
});
test('학생·일반 관리자의 검토·수정·삭제·권한 claim 위조 거절', async () => {
  await seed();
  for (const [uid, claims] of [['alice', {}], ['admin', {}], ['operator', { operator: 'true' }]]) {
    await assertFails(updateDoc(ref(uid, claims), review()));
    await assertFails(deleteDoc(ref(uid, claims)));
  }
  await assertFails(updateDoc(ref('alice'), { name: '수정' }));
  await assertFails(updateDoc(doc(db('alice'), 'users/alice'), { operator: true }));
});
test('운영자만 pending 승인/반려와 본인 검토 시각 기록 허용', async () => {
  for (const status of ['approved', 'rejected']) {
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'clubRequests/alice__request'),
      pending({ createdAt: Timestamp.fromMillis(1) })));
    await assertSucceeds(updateDoc(ref('operator', { operator: true }), review(status)));
    await assertSucceeds(getDoc(ref('alice')));
    await assertFails(updateDoc(ref('operator', { operator: true }), review(status === 'approved' ? 'rejected' : 'approved')));
    await assertFails(deleteDoc(ref('operator', { operator: true })));
  }
});
test('운영자의 내용/작성자/생성 시각 변조·잘못된 전이·검토자 위조 거절', async () => {
  await seed();
  const operator = ref('operator', { operator: true });
  for (const extra of [{ status: 'pending' }, { status: 'unknown' }, { name: '변조' }, { purpose: '변조' },
    { applicantId: 'operator' }, { reviewedBy: 'alice' }, { reviewedAt: Timestamp.fromMillis(0) },
    { createdAt: Timestamp.fromMillis(0) }, { reviewedBy: deleteField() }, { extra: true }]) {
    await assertFails(updateDoc(operator, review('approved', extra)));
  }
  await assertSucceeds(updateDoc(operator, review()));
});
