# GitHub Actions build

This repository can build the Android debug APK in GitHub Actions, so a local Android Studio or Android SDK is not required.

## How to build

1. Push the project to the `main` branch.
2. Open the repository on GitHub.
3. Go to `Actions`.
4. Select `Build Android Debug APK`.
5. Open the latest workflow run.
6. After it succeeds, download the artifact named `moment-companion-debug-apk`.
7. Unzip the artifact and install `app-debug.apk` on an Android device.

## Output

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install on Android

```bash
adb install -r app-debug.apk
```

The app still needs runtime permissions on the phone: screen capture authorization, notification permission, and optional overlay permission for the floating button.
