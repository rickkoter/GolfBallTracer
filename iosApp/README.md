# GolfBallTracer for iPhone

The iPhone version of GolfBallTracer, in SwiftUI. Its tracking code is the Android app's own, shared from
Kotlin (`../shared`) and built into `GolfTracking.framework` by Gradle as part of each Xcode build.

**What it does:** everything the Android app does, laid out the same way, with the same tracking code.

- **Tracer View:** the camera fills the screen. The card at the top has the brand (the shot's distance once
  revealed), the golfer (1 to 4) and club the next shot is tagged with, and the GPS buttons to lock the tee
  and pin the ball (the distance follows you as you walk to it). The gear opens Settings. The status banner
  says what's going on (listening, a shot being followed or worked out, the shot saved), with the
  microphone's level, video, recordings used, how far behind the detector is, and notes about strikes heard,
  saving and the video re-trace. **Start listening** / **Stop listening**, then **Locate Ball** and
  **Review** once a shot is in. After each shot the tracer is drawn over a still of the swing and listening
  stops until it's started again. The recording-limit and keep/discard prompts are as on Android.
- **Each shot** keeps its video from 2.5 s before the strike (the camera records all the time it's
  listening, sound included), its analysis frames (`capture.zip`), `tracking.json`, and a snapshot with the
  tracer and shot card baked in. It's traced again from the full video (the shared `VideoRetracer`, decoded
  with AVFoundation), and the better trace kept. The landing is estimated from the launch for the ball
  locator. A shot-debug zip goes to Files › GolfBallTracer, and strikes heard during a swing without a shot
  save a missed-strike zip there.
- **Settings:** flash, hear the strike (microphone), tune the curve by dragging, tracer style, start/stop a
  debug recording (up to 45 s of video, sound, frames and what the detector did, to Files, with notes), the
  demo flight, and **Replay recordings** (plays back recordings from either app through the shot detection).
- **Shot Log:** totals (shots, average and longest distance, recordings used), every shot with its snapshot,
  club, golfer, date and distance, finding a past shot's ball, delete, share every recording (golf-shot
  zips), export the history as CSV, and press-and-hold to select shots and export their videos with the
  tracer drawn in (saved to Photos, then Share).
- **Shot Review:** the video on a loop with the tracer growing with the ball (or the snapshot), where the
  tracer came from, distance, club, golfer and GPS, Locate Ball, Record Next Shot, and export the video,
  share the recording, share the snapshot.

Differences from Android: videos are saved to Photos (Android: Movies/GolfTracker), and recordings to the
app's folder in Files (Android: Downloads/GolfBallTracer).

How it fits together: `CameraController` reads each 1080p frame's grey plane straight into the shared
`CameraFrameSampler` (turned upright and cropped to the screen, about 640 pixels tall) and hands every frame
to `ShotVideoRecorder` (AVAssetWriter); `StrikeMicrophone` feeds the shared strike detector in measurement
mode (no voice processing); `ShotDetector` runs the shared `ShotTrigger` on its own queue; frames and strikes
share the host clock. `TrackerModel` is the Tracer View's logic (Android's TrackerScreen), `ShotProcessor`
saves and re-traces shots, and `ShotStore` keeps them as the Android app does (`shot_data/<id>.json`,
`shot_media/<id>/`). `DeviceOrientation` gives the camera's pose and compass heading; `LocationService` the
GPS.

Frame rate: the camera films 1080p at the fastest rate it has, up to 60 frames a second (newer iPhones;
the iPad 9th generation and older phones film at 30). The shared `FramePacer` passes every frame while the
detector keeps up, drops to 30 once it's a second behind, and tries 60 again later. On the Oct 18 range
sessions (60 fps video) every ball and flight was found at 60 fps, while every other frame (30 fps) missed
one ball and one flight. Rates above 60 aren't used: no footage has been checked there.

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
ones (a shot recording replays in under a second), and live it analyses every frame its camera gives (30 a second).

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
(`LIVE-CHECK` lines). The first analysis frame is saved as `Documents/live-check.png`. Two more checks print
`DEV-CHECK` lines: `-videoCheck` traces a shot recording copied into `Documents/video-check` (`video.mp4`,
`tracking.json`, `capture.zip` from a shot's `shot_media` folder, from either app) again from its video and
exports it with its tracer (`exported.mp4`, with frames before and after the strike as PNGs); `-debugCheck`
makes an 8 s debug recording. On the iPad, a Pixel shot re-traced exactly as on Android (60 fps, followed
0.5 s, same alignment), and the iPad's own debug recording replayed in the Android tests:

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
