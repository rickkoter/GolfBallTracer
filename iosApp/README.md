# GolfBallTracer for iPhone

The iPhone version of GolfBallTracer, in SwiftUI. Its tracking code is the Android app's own, shared from
Kotlin (`../shared`) and built into `GolfTracking.framework` by Gradle as part of each Xcode build.

**What it does so far:**

- **Tracer tab, live:** the back camera fills the screen with the club and golfer at the top. Tap
  **Start listening** and hit: the strike is heard on the microphone, the ball is seen leaving the tee, and
  the flight is followed and fitted, all with the Android app's shared code and thresholds. When it's done
  the tracer is drawn over a picture of the shot and listening stops until **Start listening** is tapped
  again (as on Android). Every shot is saved as a recording in the Android format (`shot_*.zip`).
- **Recordings tab:** plays back recordings from either app (`shot_*.zip`, `shot-debug_*.zip`,
  `missed-strike_*.zip`, `golf-shot_*.zip`, `debug-session_*.zip`) through the same shot detection, showing
  the ball ringed on the tee and its flight, solid where seen and dashed where estimated. Each can be
  shared (swipe right, or the share button in its playback), to send to whoever is looking into tracking.

How it fits together: `CameraController` reads each 1080p frame's grey plane straight into the shared
`CameraFrameSampler` (turned upright and cropped to the screen, about 640 pixels tall, 30 frames a second);
`StrikeMicrophone` feeds the microphone to the shared strike detector in measurement mode (no voice
processing); `ShotDetector` runs the shared `ShotTrigger` on its own queue; frames and strikes share the
host clock. `MotionSensor` gives the camera's pose for estimated flights and drops strikes heard with the
phone pointing at the ground.

**Still to come:** GPS distances and the ball locator, the video recording and re-trace, the shot log
with stats, and debug recordings.

## Running it

1. Open `GolfBallTracer.xcodeproj` in Xcode, pick an iPhone simulator, and Run. The first build downloads
   the Kotlin/Native toolchain (about 1.5 GB) and takes a few minutes; later builds are quick.
2. To run on an iPhone or iPad: connect it, turn on **Developer Mode** on it, pick it as the run destination,
   and Run. Builds are signed with the development team set in `project.yml` (`DEVELOPMENT_TEAM`, a free
   personal team), whose Apple ID must be added in **Xcode › Settings › Accounts**. The first time, trust the
   developer on the device in **Settings › General › VPN & Device Management**. With a free team an install
   stops opening after 7 days; run it from Xcode again to renew it.
3. The scheme runs in **Release**, so the shared Kotlin code is built optimised: unoptimised, it falls
   behind the camera (about 20 frames a second instead of 30, with frames queueing up).

To get the app to testers who aren't connected to this Mac, use TestFlight: see
**[TESTFLIGHT.md](TESTFLIGHT.md)**.

The app runs on iPhone and iPad, upright only. On an iPad (9th generation) replays matched the Android
ones (a shot recording replays in under a second), and live it analyses all 30 frames a second.

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

Launched with `-liveCheck`, the app starts listening by itself and prints, every two seconds, the frames
analysed per second, their size and how many are waiting, plus each strike heard and each detector event
(`LIVE-CHECK` lines). The first analysis frame is saved as `Documents/live-check.png`:

```bash
xcrun devicectl device process launch --console --terminate-existing --device <id> com.koterapps.golfballtracer -- -liveCheck
xcrun devicectl device copy from --device <id> --domain-type appDataContainer \
  --domain-identifier com.koterapps.golfballtracer --source Documents/live-check.png --destination live-check.png
```

## The Xcode project

`GolfBallTracer.xcodeproj` is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen).
After adding or removing Swift files, or changing settings, run:

```bash
brew install xcodegen   # once
cd iosApp && xcodegen
```

Command-line build for a connected device (Release, see above), and install:

```bash
xcodebuild -project GolfBallTracer.xcodeproj -scheme GolfBallTracer -configuration Release \
  -destination 'id=<device id>' -derivedDataPath build/device -allowProvisioningUpdates build
xcrun devicectl device install app --device <device id> build/device/Build/Products/Release-iphoneos/GolfBallTracer.app
```

Command-line build for the simulator:

```bash
xcodebuild -project GolfBallTracer.xcodeproj -scheme GolfBallTracer -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```
