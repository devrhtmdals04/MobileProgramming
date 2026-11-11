import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, setDoc, getDoc, getDocs, updateDoc, deleteDoc, serverTimestamp, Timestamp, deleteField } from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-mope',
    firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') },
  });
});
beforeEach(async () => { await env.clearFirestore(); });
after(async () => { await env?.cleanup(); });

const db = (uid) => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const profile = (extra = {}) => ({ displayName: '승민', role: 'student', createdAt: serverTimestamp(), ...extra });
async function seed(role = 'student') {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users/alice'), profile({ role }));
  });
}

test('본인 학생 프로필 생성·조회·이름 수정 허용', async () => {
  const ref = doc(db('alice'), 'users/alice');
  await assertSucceeds(setDoc(ref, profile()));
  await assertSucceeds(getDoc(ref));
  await assertSucceeds(updateDoc(ref, { displayName: '민성' }));
});

test('비로그인 읽기·생성·수정·삭제 거절', async () => {
  await seed();
  const ref = doc(db(), 'users/alice');
  await assertFails(getDoc(ref));
  await assertFails(setDoc(doc(db(), 'users/guest'), profile()));
  await assertFails(updateDoc(ref, { displayName: '변경' }));
  await assertFails(deleteDoc(ref));
});

test('타인 읽기·생성·수정·삭제와 사용자 목록 조회 거절', async () => {
  await seed();
  const ref = doc(db('bob'), 'users/alice');
  await assertFails(getDoc(ref));
  await assertFails(setDoc(doc(db('bob'), 'users/charlie'), profile()));
  await assertFails(updateDoc(ref, { displayName: '변경' }));
  await assertFails(deleteDoc(ref));
  await assertFails(getDocs(collection(db('alice'), 'users')));
});

test('관리자 역할로 최초 생성 및 운영 권한 필드 삽입 거절', async () => {
  const ref = doc(db('alice'), 'users/alice');
  await assertFails(setDoc(ref, profile({ role: 'club_admin' })));
  await assertFails(setDoc(ref, profile({ operator: true })));
  await assertFails(setDoc(ref, profile({ role: 1 })));
  await assertFails(setDoc(ref, profile({ role: 'operator' })));
});

test('역할 변경·생성 시각 변경·필드 추가·필수 필드 삭제·본인 삭제 거절', async () => {
  await seed();
  const ref = doc(db('alice'), 'users/alice');
  for (const change of [
    { role: 'club_admin' }, { createdAt: Timestamp.fromMillis(0) },
    { operator: true }, { email: 'a@b.com' }, { role: deleteField() },
  ]) await assertFails(updateDoc(ref, change));
  await assertFails(deleteDoc(ref));
  await assertFails(setDoc(ref, profile({ role: 'club_admin' })));
});

test('신뢰된 환경이 지정한 관리자는 이름만 수정 가능', async () => {
  await seed('club_admin');
  const ref = doc(db('alice'), 'users/alice');
  await assertSucceeds(updateDoc(ref, { displayName: '관리자' }));
  await assertFails(updateDoc(ref, { role: 'student' }));
});

test('이름 길이·타입·공백 및 위조 시각·필드 누락 거절', async () => {
  const ref = doc(db('alice'), 'users/alice');
  for (const displayName of ['', '   ', '\t\n', '가'.repeat(31), 123, null]) {
    await assertFails(setDoc(ref, profile({ displayName })));
  }
  await assertFails(setDoc(ref, profile({ createdAt: Timestamp.fromMillis(0) })));
  await assertFails(setDoc(ref, { displayName: '승민', role: 'student' }));
  await assertSucceeds(setDoc(ref, profile({ displayName: '가'.repeat(30) })));
  await assertFails(updateDoc(ref, { displayName: '' }));
  await assertFails(updateDoc(ref, { displayName: ' ' }));
  await assertSucceeds(updateDoc(ref, { displayName: '가' }));
});

test('정의되지 않은 컬렉션과 사용자 하위 경로 거절', async () => {
  for (const path of ['promotions/post1', 'users/alice/private/data']) {
    const ref = doc(db('alice'), path);
    await assertFails(setDoc(ref, { value: true }));
    await assertFails(getDoc(ref));
  }
});
