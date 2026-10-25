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

# Uploading needs the paid team, signed in to Xcode; a free personal team has no App Store profiles.
if [[ ! "$TEAM" =~ ^[A-Z0-9]{10}$ ]]; then
  echo "DEVELOPMENT_TEAM in project.yml is \"$TEAM\": a team ID is 10 letters and digits (developer.apple.com/account › Membership details)."
  exit 1
fi
TEAMS=$(defaults read com.apple.dt.Xcode IDEProvisioningTeamByIdentifier 2>/dev/null || true)
if ! grep -q "teamID = $TEAM;" <<< "$TEAMS"; then
  echo "Team $TEAM isn't in Xcode. Add the Apple ID that belongs to the paid Apple Developer Program team in"
  echo "Xcode › Settings › Accounts, then run this again. Teams Xcode knows:"
  grep -E "teamID|teamName" <<< "$TEAMS" | sed 's/^ */  /'
  exit 1
fi
if grep -B1 "teamID = $TEAM;" <<< "$TEAMS" | grep -q "isFreeProvisioningTeam = 1"; then
  # Xcode keeps this from its last look: an individual who joins the paid program keeps the same team ID,
  # so it can still say "free" until Xcode refreshes. A free team really can't make App Store profiles
  # ("No profiles for 'com.koterapps.golfballtracer'"); then check developer.apple.com › Membership details.
  echo "Note: Xcode last saw team $TEAM as a free personal team; carrying on in case it has joined the paid program since."
fi

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
