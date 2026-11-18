import { readFile } from 'node:fs/promises';
import { before, after, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, collection, setDoc, getDoc, getDocs, updateDoc, deleteDoc, query, where, writeBatch, serverTimestamp } from 'firebase/firestore';
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-mope', firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    for (const uid of ['alice', 'bob', 'operator']) await setDoc(doc(ctx.firestore(), 'users', uid), { displayName: uid, role: 'student', createdAt: serverTimestamp() });
    await setDoc(doc(ctx.firestore(), 'operators/operator'), { enabled: true });
  });
});
const path = uid => `clubAdminRequests/ssu-1__${uid}`;
const pending = uid => ({ clubId:'ssu-1', clubName:'사진부', applicantId:uid, reason:'대표자로 신청', status:'pending', createdAt:serverTimestamp(), reviewedBy:null, reviewedAt:null, verifiedChannel:'', reviewNote:'' });
const review = (uid, status='approved') => ({ status, reviewedBy:uid, reviewedAt:serverTimestamp(), verifiedChannel:status==='approved'?'https://clubunion.ssu.ac.kr/clubs/1':'', reviewNote:'공식 채널로 확인' });
const club = uid => ({ name:'사진부', category:'학술', description:'소개', recruitment:'모집 중', adminId:uid, sourceUrl:'https://clubunion.ssu.ac.kr/clubs/1', updatedAt:serverTimestamp() });
const apply = uid => setDoc(doc(db(uid),path(uid)),pending(uid));
function approve(actor='operator', applicant='alice', extra={}) {
  const store=db(actor), batch=writeBatch(store);
  batch.set(doc(store,'clubs/ssu-1'),club(applicant));
  batch.update(doc(store,path(applicant)),{...review(actor),...extra});
  return batch.commit();
}
test('운영자 명부는 본인 조회만 허용하고 자기 권한 상승·목록·삭제 차단', async () => {
  await assertSucceeds(getDoc(doc(db('operator'),'operators/operator')));
  await assertFails(getDoc(doc(db('alice'),'operators/operator')));
  await assertFails(getDocs(collection(db('operator'),'operators')));
  for (const uid of ['alice','operator']) {
    await assertFails(setDoc(doc(db(uid),`operators/${uid}`),{enabled:true}));
    await assertFails(deleteDoc(doc(db(uid),'operators/operator')));
  }
});
test('본인 신청과 조회, 운영자 대기 조회만 허용', async () => {
  await assertSucceeds(apply('alice'));
  await assertSucceeds(getDocs(query(collection(db('alice'),'clubAdminRequests'),where('applicantId','==','alice'))));
  await assertSucceeds(getDocs(query(collection(db('operator'),'clubAdminRequests'),where('status','==','pending'))));
  await assertFails(getDoc(doc(db('bob'),path('alice'))));
  await assertFails(getDocs(collection(db('alice'),'clubAdminRequests')));
  await assertFails(setDoc(doc(db('bob'),path('alice')),pending('alice')));
  await assertFails(setDoc(doc(db(),path('alice')),pending('alice')));
  await assertFails(updateDoc(doc(db('alice'),path('alice')),{status:'approved'}));
});
test('승인과 소유자 연결은 원자적으로만 허용하며 자기 승인 차단', async () => {
  await apply('alice');
  await assertFails(updateDoc(doc(db('operator'),path('alice')),review('operator')));
  await assertFails(setDoc(doc(db('operator'),'clubs/ssu-1'),club('alice')));
  await assertFails(approve('bob'));
  await assertFails(approve('operator','alice',{verifiedChannel:''}));
  await assertSucceeds(approve());
  await assertFails(approve());
  await apply('operator').then(()=>{throw Error('Assigned club must reject new applications');},()=>{});
});
test('운영자 자신의 신청도 다른 운영자의 검토가 필요', async () => {
  await apply('operator');
  await assertFails(approve('operator','operator'));
});
test('승인된 소유자만 소개·모집 편집 가능하며 소유자 변경과 탈취 차단', async () => {
  await apply('alice'); await apply('bob'); await approve();
  await assertSucceeds(updateDoc(doc(db('alice'),'clubs/ssu-1'),{description:'새 소개',recruitment:'신규 모집',updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(db('bob'),'clubs/ssu-1'),{description:'변조',updatedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(db('alice'),'clubs/ssu-1'),{adminId:'bob',updatedAt:serverTimestamp()}));
  await assertFails(approve('operator','bob'));
  await assertFails(deleteDoc(doc(db('alice'),'clubs/ssu-1')));
});
test('반려 후 재신청은 신원과 생성 시각을 유지하고 검토 기록 초기화', async () => {
  await apply('alice');
  await assertSucceeds(updateDoc(doc(db('operator'),path('alice')),review('operator','rejected')));
  await assertSucceeds(updateDoc(doc(db('alice'),path('alice')),{reason:'보완 신청',status:'pending',reviewedBy:null,reviewedAt:null,verifiedChannel:'',reviewNote:''}));
  await assertFails(updateDoc(doc(db('alice'),path('alice')),{applicantId:'bob'}));
});
test('운영자 명부 해제 즉시 검토 권한 회수', async () => {
  await apply('alice');
  await env.withSecurityRulesDisabled(ctx=>updateDoc(doc(ctx.firestore(),'operators/operator'),{enabled:false}));
  await assertFails(approve());
  await assertFails(getDoc(doc(db('operator'),path('alice'))));
});
