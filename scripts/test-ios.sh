#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DSH_IOS_SIMULATOR_ID:?Set DSH_IOS_SIMULATOR_ID to an available arm64 iOS simulator UUID}"
xcodegen generate --spec iosApp/project.yml
result_path=".local/ios-ui-$(date +%Y%m%d-%H%M%S).xcresult"
xcodebuild -project iosApp/DshMobile.xcodeproj -scheme DshMobile \
    -destination "platform=iOS Simulator,id=$DSH_IOS_SIMULATOR_ID" \
    -derivedDataPath .local/DerivedData -resultBundlePath "$result_path" \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= ARCHS=arm64 \
    -only-testing:DshMobileUITests/DshMobileUITests/testLaunchDevicesAndSettings \
    -only-testing:DshMobileUITests/DshMobileUITests/testScannerUnavailableReturnsToDevices \
    test "$@"
