#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'HELP'
Build a personally signed iOS Debug app using an existing Xcode signing setup.

Required environment:
  DSH_DEVELOPMENT_TEAM    Your 10-character Apple Developer Team ID.
Optional environment:
  DSH_IOS_DEVICE_ID       Explicit devicectl device identifier for --install.
  DSH_IOS_BUNDLE_ID       Override the bundle ID (default: org.harnesslink.app).
  DSH_IOS_DERIVED_DATA    Reuse an existing DerivedData/package cache directory.

Usage:
  scripts/build-ios-device.sh [--personal-team] [--device ID] [--archive] [--export-ipa] [--install]

--archive     Create a Debug, development-signed .xcarchive.
--export-ipa  Also export a development IPA with Xcode's debugging export method.
--install     Install the built .app with devicectl; requires an explicit device ID.
--personal-team  Build without the APNs entitlement for free Personal Team testing.
                 P2P stays enabled; notification setup reports that signing lacks remote push.

This script does not accept Xcode licenses, log in to Apple, create signing keys,
or enable automatic provisioning updates. Configure signing in Xcode first.
HELP
}

device_id="${DSH_IOS_DEVICE_ID:-}"
archive_app=false
export_ipa=false
install_app=false
personal_team=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)
            [[ $# -ge 2 && -n "$2" ]] || { printf 'Missing device ID after --device.\n' >&2; exit 2; }
            device_id="$2"
            shift 2
            ;;
        --archive) archive_app=true; shift ;;
        --export-ipa) archive_app=true; export_ipa=true; shift ;;
        --install) install_app=true; shift ;;
        --personal-team) personal_team=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

team_id="${DSH_DEVELOPMENT_TEAM:-}"
[[ "$team_id" =~ ^[A-Z0-9]{10}$ ]] || { printf 'Set DSH_DEVELOPMENT_TEAM to your actual 10-character Team ID.\n' >&2; exit 2; }
if $install_app && [[ -z "$device_id" ]]; then
    printf '%s\n' '--install requires --device ID or DSH_IOS_DEVICE_ID; no device is selected automatically.' >&2
    exit 2
fi
bundle_id="${DSH_IOS_BUNDLE_ID:-org.harnesslink.app}"
[[ "$bundle_id" =~ ^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$ ]] || { printf 'Invalid DSH_IOS_BUNDLE_ID.\n' >&2; exit 2; }
command -v xcodegen >/dev/null || { printf 'Install xcodegen before building (brew install xcodegen).\n' >&2; exit 1; }
xcodebuild -checkFirstLaunchStatus
xcodegen generate --spec iosApp/project.yml

output_dir="$PWD/.local/ios-device"
push_enabled=YES
if $personal_team; then output_dir="$PWD/.local/ios-device-personal"; push_enabled=NO; fi
derived_data="${DSH_IOS_DERIVED_DATA:-$output_dir/DerivedData}"
archive_path="$output_dir/DshMobile.xcarchive"
mkdir -p "$output_dir"
destination='generic/platform=iOS'
# CoreDevice identifiers printed by devicectl need not equal xcodebuild destination UDIDs.
# Build the development-signed iphoneos product generically and pass the identifier only to devicectl.
build_args=(
    -project iosApp/DshMobile.xcodeproj -scheme DshMobile
    -configuration Debug -sdk iphoneos -destination "$destination"
    -derivedDataPath "$derived_data"
    "DSH_DEVELOPMENT_TEAM=$team_id" "DEVELOPMENT_TEAM=$team_id"
    "PRODUCT_BUNDLE_IDENTIFIER=$bundle_id"
    CODE_SIGN_STYLE=Automatic CODE_SIGNING_ALLOWED=YES APS_ENVIRONMENT=development
    "DSH_PUSH_ENABLED=$push_enabled"
)
if $personal_team; then
    build_args+=("CODE_SIGN_ENTITLEMENTS=$PWD/iosApp/DshMobile.personal-team.entitlements")
fi
if $archive_app; then
    xcodebuild "${build_args[@]}" -archivePath "$archive_path" archive
    built_app="$archive_path/Products/Applications/DshMobile.app"
else
    xcodebuild "${build_args[@]}" build
    built_app="$derived_data/Build/Products/Debug-iphoneos/DshMobile.app"
fi
[[ -d "$built_app" ]] || { printf 'Expected signed app was not produced: %s\n' "$built_app" >&2; exit 1; }
codesign --verify --strict "$built_app"
printf '\nSigned development app: %s\n' "$built_app"

if $export_ipa; then
    export_options="$output_dir/ExportOptions.debugging.plist"
    cat > "$export_options" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>debugging</string>
  <key>destination</key><string>export</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>$team_id</string>
</dict></plist>
PLIST
    plutil -lint "$export_options"
    xcodebuild -exportArchive -archivePath "$archive_path" \
        -exportPath "$output_dir/ipa" -exportOptionsPlist "$export_options"
    printf '\nDebugging IPA directory: %s\n' "$output_dir/ipa"
fi

if $install_app; then
    xcrun devicectl device install app --device "$device_id" "$built_app"
else
    printf '\nTo install, rerun with --device YOUR_DEVICE_ID --install.\n'
fi
