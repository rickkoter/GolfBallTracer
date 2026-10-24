# GolfBallTracer for iPhone

The iPhone version of GolfBallTracer, in SwiftUI. Its tracking code is the Android app's own, shared from
Kotlin (`../shared`) and built into `GolfTracking.framework` by Gradle as part of each Xcode build.

**What it does so far:** plays back recordings saved by the Android app (`shot-debug_*.zip`,
`missed-strike_*.zip`, `golf-shot_*.zip` and `debug-session_*.zip`) through the same shot detection, and
shows each shot found: the ball ringed on the tee and its flight drawn over the frame, solid where the ball
was seen and dashed where it was estimated. Debug recordings also show their notes. The results match the
Android replays.

**Still to come:** the live camera and microphone, compass and GPS, saving shots, and the tracer and shot
log screens.

## Running it

1. Open `GolfBallTracer.xcodeproj` in Xcode, pick an iPhone simulator, and Run. The first build downloads
   the Kotlin/Native toolchain (about 1.5 GB) and takes a few minutes; later builds are quick.
2. To run on an iPhone or iPad: connect it, turn on **Developer Mode** on it, pick it as the run destination,
   and Run. Builds are signed with the development team set in `project.yml` (`DEVELOPMENT_TEAM`, a free
   personal team), whose Apple ID must be added in **Xcode › Settings › Accounts**. The first time, trust the
   developer on the device in **Settings › General › VPN & Device Management**. With a free team an install
   stops opening after 7 days; run it from Xcode again to renew it.

The app runs on iPhone and iPad. It was first tried on an iPad (9th generation): its replays matched the
Android ones, and a shot recording replayed in under a second.

## Adding recordings

- Tap **+** (Open) and pick zips from Files, including Google Drive where the Android app's recordings are
  shared to.
- Or use **Open in GolfBallTracer** from another app's share sheet.
- Or copy them into **GolfBallTracer** in the Files app, or with Finder's file sharing for a connected iPhone.

For the simulator, files can be copied straight into the app's Documents folder:

```bash
cp ../app/shots/…/shot-debug_*.zip "$(xcrun simctl get_app_container booted com.koterapps.golfballtracer data)/Documents/"
```

## Checking replays on a device over USB

Launched with `-replayAll`, the app replays every recording it holds and prints what each gives, so the
results can be compared with the Android replays without looking at the screen:

```bash
xcrun devicectl device copy to --device <id> --domain-type appDataContainer \
  --domain-identifier com.koterapps.golfballtracer --source shot-debug_….zip --destination Documents/shot-debug_….zip
xcrun devicectl device process launch --console --terminate-existing --device <id> com.koterapps.golfballtracer -- -replayAll
```

`xcrun devicectl list devices` gives the device's id.

## The Xcode project

`GolfBallTracer.xcodeproj` is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen).
After adding or removing Swift files, or changing settings, run:

```bash
brew install xcodegen   # once
cd iosApp && xcodegen
```

Command-line build for the simulator:

```bash
xcodebuild -project GolfBallTracer.xcodeproj -scheme GolfBallTracer -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```
