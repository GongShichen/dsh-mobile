#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
printf 'Java\n'
java -version
printf '\nGradle wrapper\n'
./gradlew --version
printf '\nAndroid SDK\n'
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
test -f "$sdk_root/platforms/android-37.0/package.xml" || { printf 'Missing complete Android API 37 installation in %s\n' "$sdk_root"; exit 1; }
"$sdk_root/platform-tools/adb" version
if [[ "$(uname -s)" == Darwin ]]; then
    printf '\nXcode\n'
    xcodebuild -version
    xcodebuild -checkFirstLaunchStatus
    xcrun --sdk iphoneos --show-sdk-version
    xcrun simctl list runtimes
fi
