import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signInWithEmailAndPassword } from 'firebase/auth';
import { getFirestore, connectFirestoreEmulator, doc, runTransaction, updateDoc, serverTimestamp, terminate } from 'firebase/firestore';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';

// 고정된 localhost demo 프로젝트만 사용한다. 운영 환경 주소·자격 증명은 받지 않는다.
const projectId = 'demo-mope';
const password = 'gru-demo-1234';
const clients = [];
let environment;
async function account(name, email, displayName) {
  const app = initializeApp({ projectId, apiKey: 'demo-mope-api-key', appId: '1:1234567890:web:gru-demo' }, name);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  const store = getFirestore(app);
  connectFirestoreEmulator(store, '127.0.0.1', 8080);
  clients.push({ app, store });
  let user;
  try { user = (await createUserWithEmailAndPassword(auth, email, password)).user; }
  catch (error) {
    if (error.code !== 'auth/email-already-in-use') throw error;
    user = (await signInWithEmailAndPassword(auth, email, password)).user;
  }
  const profile = doc(store, 'users', user.uid);
  await runTransaction(store, async tx => {
    if (!(await tx.get(profile)).exists()) tx.set(profile, { displayName, role: 'student', createdAt: serverTimestamp() });
  });
  return { store, uid: user.uid, email };
}

try {
  environment = await initializeTestEnvironment({ projectId, firestore: { host: '127.0.0.1', port: 8080 } });
  const student = await account('demo-student', 'student@gru.example', '그루 데모 학생');
  const admin = await account('demo-admin', 'admin@gru.example', '사진부 데모 관리자');
  await environment.withSecurityRulesDisabled(async context => {
    const store = context.firestore();
    const club = doc(store, 'clubs/photo');
    await runTransaction(store, async tx => {
      const existing = await tx.get(club);
      tx.set(club, { name: '찰칵 사진부', category: '문화', description: '캠퍼스의 순간을 함께 기록해요.',
        recruitment: '매주 목요일 · 초보 환영', adminId: admin.uid,
        createdAt: existing.get('createdAt') ?? serverTimestamp() });
    });
    await updateDoc(doc(store, 'users', admin.uid), { role: 'club_admin' });
  });
  const roomId = `photo__${student.uid}`;
  await runTransaction(student.store, async tx => {
    const ref = doc(student.store, 'chatRooms', roomId);
    if (!(await tx.get(ref)).exists()) tx.set(ref, { clubId: 'photo', studentId: student.uid,
      adminId: admin.uid, participantIds: [student.uid, admin.uid], createdAt: serverTimestamp() });
  });
  for (const [actor, id, text] of [
    [student, 'demo-student-question', '사진부 가입 문의합니다.'],
    [admin, 'demo-admin-reply', '환영합니다! 편하게 문의해 주세요.'],
  ]) {
    await runTransaction(actor.store, async tx => {
      const ref = doc(actor.store, 'chatRooms', roomId, 'messages', id);
      if (!(await tx.get(ref)).exists()) tx.set(ref, { senderId: actor.uid, text, createdAt: serverTimestamp() });
    });
  }
  process.stdout.write(`GRU local demo ready\nstudent: ${student.email}\nadmin: ${admin.email}\npassword: ${password}\nclubId: photo\nroomId: ${roomId}\n`);
} finally {
  for (const { app, store } of clients) { await terminate(store); await deleteApp(app); }
  await environment?.cleanup();
}
