import { spawn } from 'node:child_process';

// 서버를 공유하는 검사들은 포트 충돌 없이 순서대로 실행한다. 실패하면 즉시 중단한다.
const coreOnly = process.argv.slice(2).includes('--core');
if (process.argv.slice(2).some(arg => arg !== '--core')) throw new Error('사용법: npm run verify:backend [-- --core]');
const steps = [
  ['설정 점검 도구 테스트', 'npm', ['run', 'test:tooling']],
  ['Mock 단위 테스트·APK·Lint', './gradlew', ['-PmopeBackend=mock', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug']],
  ['Firestore 권한 회귀', 'npm', ['run', 'test:rules']],
  ['로컬 데모 반복 생성', 'npm', ['run', 'test:chat:demo']],
  ...coreOnly ? [] : [
    ['Android 인증·채팅·개설 요청', 'npm', ['run', 'test:auth:android']],
    ['실제 프로세스 재시작·로그아웃 복원', 'npm', ['run', 'test:auth:restart']],
  ],
];
for (const [label, command, args] of steps) {
  console.log(`\nGRU 검증 시작: ${label}`);
  const code = await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', timeout: 15 * 60_000 });
    child.once('error', reject);
    child.once('close', (code, signal) => resolve(signal ? 1 : code ?? 1));
  });
  if (code !== 0) { console.error(`FAIL ${label}`); process.exit(code); }
  console.log(`PASS ${label}`);
}
console.log(coreOnly ? '\nPASS 핵심 검증 완료 (Android 검증은 실행하지 않음)' : '\nPASS 우리 담당 백엔드 로컬 전체 검증 완료');
