#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DSH_IOS_SIMULATOR_ID:?Set DSH_IOS_SIMULATOR_ID to an available arm64 iOS simulator UUID}"
xcodegen generate --spec iosApp/project.yml
result_path=".local/ios-ui-$(date +%Y%m%d-%H%M%S).xcresult"
xcodebuild -project iosApp/HLApp.xcodeproj -scheme HLApp \
    -destination "platform=iOS Simulator,id=$DSH_IOS_SIMULATOR_ID" \
    -derivedDataPath .local/DerivedData -resultBundlePath "$result_path" \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM= ARCHS=arm64 \
    -only-testing:HLAppUITests/HLAppUITests/testLaunchDevicesAndSettings \
    -only-testing:HLAppUITests/HLAppUITests/testScannerUnavailableReturnsToDevices \
    -only-testing:HLAppUITests/HLAppUITests/testInputAcceptsTouchTypingAndOutsideDismissal \
    -only-testing:HLAppUITests/HLAppUITests/testManualPairingInputsAcceptTouchTyping \
    -only-testing:HLAppUITests/HLAppUITests/testScannerReturnRestoresComposerInput \
    test "$@"
