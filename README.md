# Chipmunk Chat V2.5 (Android)

The Android companion to the V2 web app. Same contacts, same encryption, same protocol, so a V2.5 phone and a V2 web-app phone can chat and call each other. The difference: V2.5 keeps running in the background and shows notifications and ringing calls.

## Build the APK (Android Studio)

You need Node.js 20+ and Android Studio (with an Android SDK installed).

1. Open a terminal in this folder and run `npm install`.
2. Run `npm run android:add`. This bundles the web code, creates the `android/` project, adds the background service and permissions, and syncs.
3. Run `npm run open` to open the project in Android Studio. Wait for Gradle to finish syncing.
4. Launcher icon: in Android Studio, right-click `app` > New > Image Asset, and pick your squirrel icon.
5. Build: Build > Build Bundle(s) / APK(s) > Build APK(s). The file is `android/app/build/outputs/apk/debug/app-debug.apk`.
6. Copy the APK to the phone and install it (allow "install unknown apps" when asked).

After you change `src/index.html`, run `npm run sync` and rebuild.

## First launch on the phone

1. Set a PIN, allow notifications.
2. Open Settings > Background and tap **Allow unrestricted battery**. Without this, Android may pause the app after a while.
3. On Xiaomi, Huawei, Samsung and OnePlus phones, also open the phone's own battery or app settings and set Chipmunk Chat to "No restrictions" and allow auto-start.

## What runs in the background

- A foreground service keeps the app alive. It shows one small status notification.
- Incoming messages raise a notification showing only the contact's name and "New message" (never the text).
- Incoming calls ring with a full-screen notification and can show over the lock screen. Tap to answer.
- The app stays unlocked in memory so it can receive. Tap **Lock now** in Settings to stop listening.
- After a phone restart or if Android kills the app, open it and enter your PIN to start listening again.

## Limits

- Both people must be connected. If a phone is off, offline, or the app was killed, nothing reaches it.
- A V2 web-app contact only receives while its page is open on screen. Install V2.5 on that phone too if it is Android.
- iPhone: use the V2 web app (no APK). Background delivery on iPhone needs the server you plan to add.
- No relay server yet, so some mobile or office networks may block calls.

## Files

- `src/index.html`: the app (shared with V2, plus Android hooks)
- `native/`: Android background service and plugin (copied into the Android project by `scripts/patch-android.js`)
- `scripts/`: build helpers
