#!/usr/bin/env bash
set -euo pipefail

if command -v ./gradlew >/dev/null 2>&1; then
  ./gradlew clean assembleDebug
elif command -v gradle >/dev/null 2>&1; then
  gradle clean assembleDebug
else
  echo "Gradle not found. Open this project in Android Studio, or install Gradle, then run again."
  exit 1
fi

echo "APK output: app/build/outputs/apk/debug/app-debug.apk"
