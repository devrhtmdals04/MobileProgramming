import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Run under Firebase emulators:exec so server-side account/profile data survives all stages.
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw new Error('Use npm run test:auth:restart to start both Firebase emulators');
}
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  || readFileSync('local.properties', 'utf8').match(/^sdk\.dir=(.+)$/m)?.[1]?.trim();
if (!sdk) throw new Error('Set ANDROID_HOME or sdk.dir in local.properties');
const adb = resolve(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 240_000, maxBuffer: 16 * 1024 * 1024 });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')}\n${result.error || ''}\n${result.stdout}\n${result.stderr}`);
  }
  return result.stdout;
}
const devices = run(adb, ['devices']).split('\n').filter(line => /\tdevice$/.test(line.trim()))
  .map(line => line.split('\t')[0]);
const serial = process.env.ANDROID_SERIAL || (devices.length === 1 ? devices[0] : null);
if (!serial || !devices.includes(serial) || !serial.startsWith('emulator-')) {
  throw new Error('Start one Android emulator, or select it with ANDROID_SERIAL');
}
const device = (...args) => run(adb, ['-s', serial, ...args]);
run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
  ['-PmopeBackend=emulator', ':app:assembleDebug', ':app:assembleDebugAndroidTest']);
device('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
device('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
for (const stage of ['seed', 'restored', 'signOut', 'loggedOut']) {
  device('shell', 'am', 'force-stop', 'com.mope.app');
  device('shell', 'am', 'force-stop', 'com.mope.app.test');
  const output = device('shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', 'com.mope.app.data.AuthProcessRestartTest#sessionStage',
    '-e', 'sessionStage', stage, 'com.mope.app.test/androidx.test.runner.AndroidJUnitRunner');
  if (!output.includes(`sessionStageResult=${stage}:passed`) || !output.includes('OK (1 test)')
      || output.includes('FAILURES!!!') || output.includes('INSTRUMENTATION_FAILED')) {
    throw new Error(`Session stage ${stage} failed\n${output}`);
  }
  process.stdout.write(`PASS ${stage}: separate app process verified\n`);
}
