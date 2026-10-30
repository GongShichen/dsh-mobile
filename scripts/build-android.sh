#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f core/transport/src/androidMain/jniLibs/arm64-v8a/libiroh_ffi.so ]]; then
    IROH_ANDROID_TARGETS=aarch64-linux-android scripts/build-iroh-android.sh
fi
./gradlew :androidApp:assembleDebug "$@"
