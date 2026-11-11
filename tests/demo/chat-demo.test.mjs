import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, doc, getDocs, getDoc } from 'firebase/firestore';

test('로컬 데모를 반복 준비해도 같은 방·두 메시지·생성 시각을 유지한다', async () => {
  const environment = await initializeTestEnvironment({ projectId: 'demo-mope',
    firestore: { host: '127.0.0.1', port: 8080 } });
  function seed() {
    const result = spawnSync(process.execPath, ['scripts/seed-chat-demo.mjs'],
      { encoding: 'utf8', timeout: 60_000 });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}\n${result.error || ''}`);
  }
  async function inspect() {
    return environment.withSecurityRulesDisabled(async context => {
      const db = context.firestore();
      const rooms = await getDocs(collection(db, 'chatRooms'));
      assert.equal(rooms.size, 1);
      const room = rooms.docs[0];
      const messages = await getDocs(collection(db, 'chatRooms', room.id, 'messages'));
      assert.equal(messages.size, 2);
      assert.equal((await getDoc(doc(db, 'clubs/photo'))).get('adminId'), room.get('adminId'));
      assert.equal((await getDoc(doc(db, 'users', room.get('adminId')))).get('role'), 'club_admin');
      const contact = await getDoc(doc(db, 'clubs', 'photo', 'publicContacts', room.get('adminId')));
      assert.deepEqual(Object.keys(contact.data()).sort(), ['displayName', 'responseHint', 'updatedAt']);
      assert.equal(contact.get('displayName'), '사진부 문의 담당');
      assert.equal((await getDoc(doc(db, 'clubs/photo'))).get('recruitmentTitle'), '신입 부원 모집');
      return { contactUpdatedAt: contact.get('updatedAt').toMillis(), id: room.id, createdAt: room.get('createdAt').toMillis(),
        messages: messages.docs.map(item => [item.id, item.get('createdAt').toMillis()]) };
    });
  }
  try {
    seed();
    const first = await inspect();
    seed();
    assert.deepEqual(await inspect(), first);
  } finally { await environment.cleanup(); }
});
