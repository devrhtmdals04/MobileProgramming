import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, setDoc, getDoc, getDocs, updateDoc, deleteDoc, serverTimestamp,
  Timestamp, query, where, orderBy, limitToLast, runTransaction } from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-mope',
    firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const store = context.firestore();
    for (const uid of ['alice', 'admin', 'bob']) {
      await setDoc(doc(store, `users/${uid}`), { displayName: uid, role: 'student', createdAt: serverTimestamp() });
    }
    await setDoc(doc(store, 'clubs/club1'), { adminId: 'admin' });
  });
});
after(async () => { await env?.cleanup(); });
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const room = (extra = {}) => ({ clubId: 'club1', studentId: 'alice', adminId: 'admin',
  participantIds: ['alice', 'admin'], createdAt: serverTimestamp(), ...extra });
const message = (senderId = 'alice', extra = {}) => ({ senderId, text: '문의합니다', createdAt: serverTimestamp(), ...extra });
const roomRef = uid => doc(db(uid), 'chatRooms/club1__alice');
const messageRef = (uid, id = 'm1') => doc(db(uid), `chatRooms/club1__alice/messages/${id}`);
const createRoom = () => assertSucceeds(setDoc(roomRef('alice'), room()));

test('학생이 정규 ID 방 생성, 두 참여자가 조회·송신·메시지 목록 조회', async () => {
  await createRoom();
  for (const uid of ['alice', 'admin']) {
    await assertSucceeds(getDoc(roomRef(uid)));
    await assertSucceeds(setDoc(messageRef(uid, uid), message(uid)));
    await assertSucceeds(getDoc(messageRef(uid, uid)));
    await assertSucceeds(getDocs(query(collection(db(uid), 'chatRooms/club1__alice/messages'),
      orderBy('createdAt'), limitToLast(50))));
  }
});

test('정규 ID의 미생성 방은 본인만 조회하고 트랜잭션에서 생성·재사용', async () => {
  const store = db('alice');
  const ref = doc(store, 'chatRooms/club1__alice');
  const missing = await assertSucceeds(getDoc(ref));
  assert.equal(missing.exists(), false);
  for (const uid of ['bob', 'admin', null]) await assertFails(getDoc(roomRef(uid)));
  const getOrCreate = () => runTransaction(store, async tx => {
    if (!(await tx.get(ref)).exists()) tx.set(ref, room());
  });
  await assertSucceeds(Promise.all([getOrCreate(), getOrCreate()]));
  await assertSucceeds(runTransaction(store, async tx => { assert.equal((await tx.get(ref)).exists(), true); }));
  await assertFails(getDoc(doc(db('alice'), 'chatRooms/no-club__alice')));
  await assertFails(getDoc(doc(db('alice'), 'chatRooms/invalid')));
  assert.equal((await getDocs(query(collection(store, 'chatRooms'), where('participantIds', 'array-contains', 'alice')))).size, 1);
});

test('방 목록은 본인 participantIds 조건으로만 조회', async () => {
  await createRoom();
  for (const uid of ['alice', 'admin']) {
    const result = await assertSucceeds(getDocs(query(collection(db(uid), 'chatRooms'), where('participantIds', 'array-contains', uid))));
    assert.equal(result.size, 1);
  }
  await assertFails(getDocs(collection(db('alice'), 'chatRooms')));
  await assertFails(getDocs(query(collection(db('bob'), 'chatRooms'), where('participantIds', 'array-contains', 'alice'))));
});

test('채팅 관리자 확인용 동아리 단건 조회만 허용, 목록·쓰기·비로그인 조회 거절', async () => {
  await assertSucceeds(getDoc(doc(db('alice'), 'clubs/club1')));
  await assertSucceeds(getDoc(doc(db('admin'), 'clubs/club1')));
  await assertFails(getDoc(doc(db(), 'clubs/club1')));
  await assertFails(getDocs(collection(db('alice'), 'clubs')));
  await assertFails(setDoc(doc(db('alice'), 'clubs/new'), { adminId: 'alice' }));
  await assertFails(updateDoc(doc(db('admin'), 'clubs/club1'), { adminId: 'alice' }));
  await assertFails(deleteDoc(doc(db('admin'), 'clubs/club1')));
});

test('비로그인·비참여자의 방·메시지 읽기/쓰기 거절', async () => {
  await createRoom();
  await setDoc(messageRef('alice'), message());
  for (const uid of [null, 'bob']) {
    await assertFails(getDoc(roomRef(uid)));
    await assertFails(setDoc(roomRef(uid), room()));
    await assertFails(getDoc(messageRef(uid)));
    await assertFails(getDocs(collection(db(uid), 'chatRooms/club1__alice/messages')));
    await assertFails(setDoc(messageRef(uid, 'intrusion'), message(uid)));
  }
});

test('관리자·학생 위조, 존재하지 않는 참조, 자기 자신과 방 생성 거절', async () => {
  for (const [uid, data, path] of [
    ['bob', room(), 'club1__alice'],
    ['admin', room({ studentId: 'admin', participantIds: ['admin', 'admin'] }), 'club1__admin'],
    ['alice', room({ adminId: 'bob', participantIds: ['alice', 'bob'] }), 'club1__alice'],
    ['alice', room({ clubId: 'missing' }), 'missing__alice'],
    ['missing-user', room({ studentId: 'missing-user', participantIds: ['missing-user', 'admin'] }), 'club1__missing-user'],
  ]) await assertFails(setDoc(doc(db(uid), `chatRooms/${path}`), data));
  await env.withSecurityRulesDisabled(async context => { await deleteDoc(doc(context.firestore(), 'users/admin')); });
  await assertFails(setDoc(roomRef('alice'), room()));
});

test('참여자 수·타입·중복·필드·시간·방 ID 위조 거절', async () => {
  for (const participantIds of [['alice'], ['alice', 'alice'], ['alice', 'admin', 'bob'], 'alice', [1, 2]]) {
    await assertFails(setDoc(roomRef('alice'), room({ participantIds })));
  }
  await assertFails(setDoc(roomRef('alice'), room({ extra: true })));
  await assertFails(setDoc(roomRef('alice'), room({ createdAt: Timestamp.fromMillis(0) })));
  await assertFails(setDoc(doc(db('alice'), 'chatRooms/arbitrary'), room()));
  const { adminId, ...missing } = room();
  await assertFails(setDoc(roomRef('alice'), missing));
  await assertFails(setDoc(roomRef('alice'), room({ clubId: 1 })));
  await assertFails(setDoc(doc(db('alice'), 'chatRooms/a__b__alice'), room({ clubId: 'a__b' })));
});

test('참여자도 방 필드 변경·삭제 불가', async () => {
  await createRoom();
  for (const uid of ['alice', 'admin']) {
    for (const change of [{ participantIds: ['alice', 'bob'] }, { clubId: 'other' }, { createdAt: serverTimestamp() }, { lastMessage: 'edit' }]) {
      await assertFails(updateDoc(roomRef(uid), change));
    }
    await assertFails(deleteDoc(roomRef(uid)));
    await assertFails(setDoc(roomRef(uid), room()));
  }
});

test('참여자 배열 순서와 무관하게 권한 검사, 새 관리자는 과거 대화 접근 불가', async () => {
  await assertSucceeds(setDoc(roomRef('alice'), room({ participantIds: ['admin', 'alice'] })));
  await setDoc(messageRef('alice'), message());
  await env.withSecurityRulesDisabled(async context => {
    await updateDoc(doc(context.firestore(), 'clubs/club1'), { adminId: 'bob' });
  });
  await assertSucceeds(getDoc(messageRef('admin')));
  await assertSucceeds(getDoc(messageRef('alice')));
  await assertFails(getDoc(roomRef('bob')));
  await assertFails(getDoc(messageRef('bob')));
  await assertFails(updateDoc(roomRef('alice'), { adminId: 'bob', participantIds: ['alice', 'bob'] }));
});

test('메시지 발신자·시각·필드 위조, 필수 필드 누락 및 미생성 방 송신 거절', async () => {
  await assertFails(setDoc(messageRef('alice'), message()));
  await createRoom();
  for (const data of [message('admin'), message('alice', { createdAt: Timestamp.fromMillis(0) }),
    message('alice', { operator: true }), { senderId: 'alice', text: 'missing timestamp' }]) {
    await assertFails(setDoc(messageRef('alice'), data));
  }
});

test('메시지 내용 경계와 여러 줄 본문 검사', async () => {
  await createRoom();
  for (const text of ['', '  ', '\t\n', '가'.repeat(2001), 123, null]) {
    await assertFails(setDoc(messageRef('alice'), message('alice', { text })));
  }
  for (const [index, text] of ['가', '가'.repeat(2000), '첫 줄\n둘째 줄'].entries()) {
    await assertSucceeds(setDoc(messageRef('alice', `valid-${index}`), message('alice', { text })));
  }
});

test('메시지 수정·삭제·동일 ID 덮어쓰기 거절, 기존 ID 재조회 허용', async () => {
  await createRoom();
  await setDoc(messageRef('alice'), message());
  for (const uid of ['alice', 'admin']) {
    await assertFails(updateDoc(messageRef(uid), { text: 'edit' }));
    await assertFails(deleteDoc(messageRef(uid)));
    await assertFails(setDoc(messageRef(uid), message(uid)));
    await assertSucceeds(getDoc(messageRef(uid)));
  }
});
