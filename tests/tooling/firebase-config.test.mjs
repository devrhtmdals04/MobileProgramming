import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkFirebaseConfig } from '../../scripts/check-firebase-config.mjs';
const valid = () => ({ project_info: { project_id: 'gru-development', project_number: '123456' },
  client: [{ client_info: { mobilesdk_app_id: '1:123456:android:example',
    android_client_info: { package_name: 'com.mope.app' } }, api_key: [{ current_key: 'fixture-not-a-real-key' }] }] });
test('대상 프로젝트·패키지·앱 ID가 일치하는 설정 허용', () => {
  assert.equal(checkFirebaseConfig(valid(), 'gru-development'), 'gru-development');
});
test('명시적 대상 누락·Emulator·다른 프로젝트 설정 거절', () => {
  for (const project of [undefined, '', 'demo-mope', 'different-project']) assert.throws(() => checkFirebaseConfig(valid(), project));
});
test('다른 패키지·프로젝트 번호의 Android 앱과 불완전 설정 거절', () => {
  for (const mutate of [
    config => { config.client[0].client_info.android_client_info.package_name = 'other.app'; },
    config => { config.client[0].client_info.mobilesdk_app_id = '1:999:android:other'; },
    config => { config.client[0].api_key = []; },
    config => { delete config.project_info.project_number; },
    config => { config.client = []; },
  ]) { const config = valid(); mutate(config); assert.throws(() => checkFirebaseConfig(config, 'gru-development')); }
});
