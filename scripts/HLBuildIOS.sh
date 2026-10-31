#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v xcodegen >/dev/null; then
    printf 'xcodegen is required to regenerate the Xcode project (brew install xcodegen).\n' >&2
    exit 1
fi
xcodegen generate --spec iosApp/project.yml
xcodebuild -project iosApp/HLApp.xcodeproj -scheme HLApp \
    -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath .local/DerivedData CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= ARCHS=arm64 "$@"
