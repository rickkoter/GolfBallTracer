# GolfBallTracer

An Android and iPhone app that turns your phone's camera into a golf shot tracer. Set the phone up behind the golfer and just hit: the app hears the club strike the ball, sees the ball leave the tee, and draws a glowing tracer along its flight.

- **Hands-free shot detection**: a shot starts on the sound of the strike together with the ball leaving. There's nothing to tap.
- **Ball flight tracing**: the flight is traced live, then again from the shot's full-resolution video, and the better trace is kept.
- **Shot recordings**: every shot keeps its video and tracking data. Play it back with the tracer, or share it as a zip for later analysis. Recordings are kept for up to 32 shots.
- **Distance and ball locator**: GPS tee and landing pins give the shot distance, and the launch on camera gives an estimate of where the ball came down.
- **Shot history**: snapshots with the tracer, club and distance stats, and CSV export.

See the **[User Manual](USER_MANUAL.md)** for setup, how to record shots, and every feature in detail.

## Project layout

| Folder | What's in it |
|---|---|
| `shared/` | The tracking code, in Kotlin Multiplatform, used by both apps: hearing the strike, finding the ball at the strike, following it, fitting its flight, tracing again from the video, launch physics, the shot model and file formats, and replaying saved recordings. |
| `app/` | The Android app (Jetpack Compose, CameraX): camera, microphone, GPS, storage and screens. |
| `iosApp/` | The iPhone and iPad app (SwiftUI), with the same features and layout as the Android app (tracer view, settings, debug recordings, demo flight, shot log and review, exports), all through the shared tracking code. See [iosApp/README.md](iosApp/README.md); for testers, [iosApp/TESTFLIGHT.md](iosApp/TESTFLIGHT.md). |

## Building

Android, minimum Android 8.0:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/golfballtracer-debug.apk`. Unit tests run with `./gradlew :app:testDebugUnitTest`. Saved shot recordings can be replayed with `ShotReplayTest` (see the manual).

iPhone: needs Xcode on a Mac. Open `iosApp/GolfBallTracer.xcodeproj` and run; Xcode builds the shared Kotlin code with Gradle as part of the build. Testers get it through TestFlight (`iosApp/scripts/testflight.sh`, see [iosApp/TESTFLIGHT.md](iosApp/TESTFLIGHT.md)).
