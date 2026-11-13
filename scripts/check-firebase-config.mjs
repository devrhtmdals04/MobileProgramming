import { readFileSync } from 'node:fs';

// 읽기 전용 사전 점검. 자격 증명·토큰을 출력하거나 프로젝트에 접근하지 않는다.
export function checkFirebaseConfig(config, expectedProject) {
  if (!expectedProject || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(expectedProject) || expectedProject.startsWith('demo-')) {
    throw new Error('실제 대상 project ID를 --project로 명시하세요 (demo-* 제외)');
  }
  const info = config.project_info;
  if (info?.project_id !== expectedProject) throw new Error('설정 파일과 대상 project ID가 다릅니다');
  if (!/^\d+$/.test(info?.project_number ?? '')) throw new Error('project_number가 없습니다');
  const client = config.client?.find(item => item.client_info?.android_client_info?.package_name === 'com.mope.app');
  if (!client) throw new Error('com.mope.app Android 클라이언트가 없습니다');
  if (!client.client_info?.mobilesdk_app_id?.startsWith(`1:${info.project_number}:android:`)) {
    throw new Error('Android app ID와 project_number가 일치하지 않습니다');
  }
  if (!client.api_key?.some(key => typeof key.current_key === 'string' && key.current_key.trim())) {
    throw new Error('Android API 키 설정이 없습니다');
  }
  return expectedProject;
}

// import 시에는 실행하지 않아 검증 함수를 가짜 설정으로 테스트할 수 있다.
import { pathToFileURL } from 'node:url';
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 2 || args[0] !== '--project') throw new Error('사용법: npm run check:firebase -- --project 실제-project-id');
    const config = JSON.parse(readFileSync('app/google-services.json', 'utf8'));
    const project = checkFirebaseConfig(config, args[1]);
    console.log(`PASS Firebase 로컬 설정: ${project}, com.mope.app`);
    console.log('정적 설정만 확인했습니다. Auth·Rules·인덱스 배포와 실제 통신 검증은 별도입니다.');
  } catch (error) {
    // 파싱 오류 원문에 JSON/키가 포함될 수 있으므로 출력하지 않는다.
    const message = error instanceof SyntaxError ? '설정 파일 JSON 형식이 잘못됐습니다' :
      error.code === 'ENOENT' ? 'app/google-services.json이 없습니다. Firebase 콘솔에서 Android 설정을 받으세요' : error.message;
    console.error(`FAIL ${message}`);
    process.exitCode = 1;
  }
}
