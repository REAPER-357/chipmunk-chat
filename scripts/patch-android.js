// Run after "cap add android". Safe to run again.
// Adds the background service, notifications, permissions and the native plugin to the generated Android project.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const cfg = JSON.parse(fs.readFileSync(path.join(root, 'capacitor.config.json'), 'utf8'));
const pkg = cfg.appId;
const androidDir = path.join(root, 'android');
if (!fs.existsSync(androidDir)) { console.error('No android/ folder yet. Run "npx cap add android" first.'); process.exit(1); }

// 1. Java sources
const javaDir = path.join(androidDir, 'app', 'src', 'main', 'java', ...pkg.split('.'));
fs.mkdirSync(javaDir, { recursive: true });
for (const f of ['ChipmunkService.java', 'ChipmunkBackgroundPlugin.java', 'MainActivity.java']) {
  const src = fs.readFileSync(path.join(root, 'native', f), 'utf8').replace(/__PKG__/g, pkg);
  fs.writeFileSync(path.join(javaDir, f), src);
}

// 2. Status-bar icon
const drawDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.writeFileSync(path.join(drawDir, 'ic_stat_chipmunk.xml'),
`<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z"/>
</vector>
`);

// 3. Manifest
const mp = path.join(androidDir, 'app', 'src', 'main', 'AndroidManifest.xml');
let m = fs.readFileSync(mp, 'utf8');
if (m.includes('.ChipmunkService')) {
  console.log('Manifest already patched.');
} else {
  const perms = ['INTERNET', 'ACCESS_NETWORK_STATE', 'CAMERA', 'RECORD_AUDIO', 'MODIFY_AUDIO_SETTINGS', 'POST_NOTIFICATIONS',
    'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'FOREGROUND_SERVICE_MICROPHONE', 'FOREGROUND_SERVICE_CAMERA',
    'WAKE_LOCK', 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'USE_FULL_SCREEN_INTENT', 'VIBRATE'];
  let add = perms.filter(p => !m.includes('android.permission.' + p + '"'))
    .map(p => '    <uses-permission android:name="android.permission.' + p + '" />').join('\n');
  add += '\n    <uses-feature android:name="android.hardware.camera" android:required="false" />' +
         '\n    <uses-feature android:name="android.hardware.microphone" android:required="false" />';
  const service = `        <service
            android:name=".ChipmunkService"
            android:exported="false"
            android:foregroundServiceType="specialUse|microphone|camera">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps an end-to-end encrypted peer-to-peer connection open so messages and calls from your contacts arrive while the app is in the background" />
        </service>
`;
  if (!m.includes('</application>') || !m.includes('</manifest>')) { console.error('Unexpected AndroidManifest.xml layout'); process.exit(1); }
  m = m.replace('</application>', service + '    </application>');
  m = m.replace('</manifest>', add + '\n</manifest>');
  // Do not let Android copy the app data (contacts, secrets) to cloud backups.
  m = m.replace('android:allowBackup="true"', 'android:allowBackup="false"');
  fs.writeFileSync(mp, m);
  console.log('Manifest patched.');
}
if (!/launchMode="singleTask"/.test(m)) console.warn('! MainActivity is not singleTask. Notification taps may open a second copy of the app.');
console.log('Android project patched for package', pkg);
