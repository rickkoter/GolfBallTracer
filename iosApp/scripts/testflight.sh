#!/bin/zsh
# Builds the iOS app for release and uploads it to App Store Connect, where it shows up in TestFlight
# a few minutes later. Needs the paid Apple Developer Program, the app's record in App Store Connect,
# and the developer Apple ID signed in to Xcode (Settings › Accounts). See iosApp/TESTFLIGHT.md.
#
#   iosApp/scripts/testflight.sh           # build number = date and time, e.g. 202610241930
#   iosApp/scripts/testflight.sh 42        # or a build number of your own (it must go up every upload)
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD=${1:-$(date +%Y%m%d%H%M)}
ARCHIVE=build/GolfBallTracer.xcarchive
TEAM=$(awk '/DEVELOPMENT_TEAM:/ {print $2}' project.yml)

xcodegen -q
rm -rf "$ARCHIVE" build/export
echo "Archiving build $BUILD for team $TEAM…"
xcodebuild -project GolfBallTracer.xcodeproj -scheme GolfBallTracer -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates CURRENT_PROJECT_VERSION="$BUILD" \
  archive | grep -E "error:|warning: .*GolfBallTracer/|ARCHIVE (SUCCEEDED|FAILED)" || true
[[ -d "$ARCHIVE" ]] || { echo "The archive failed; run the xcodebuild line above without the grep to see why."; exit 1; }

echo "Uploading to App Store Connect…"
cp scripts/ExportOptions.plist build/ExportOptions.plist
/usr/libexec/PlistBuddy -c "Add :teamID string $TEAM" build/ExportOptions.plist
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist build/ExportOptions.plist \
  -exportPath build/export -allowProvisioningUpdates
echo "Uploaded build $BUILD. It appears in App Store Connect › TestFlight once Apple has processed it (usually 5–30 minutes)."
