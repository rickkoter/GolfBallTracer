# GolfBallTracer

An Android app that turns your phone's camera into a golf shot tracer. Set the phone up behind the golfer and just hit: the app hears the club strike the ball, sees the ball leave the tee, and draws a glowing tracer along its flight.

- **Hands-free shot detection**: a shot starts on the sound of the strike together with the ball leaving. There's nothing to tap.
- **Ball flight tracing**: the flight is traced live, then again from the shot's full-resolution video, and the better trace is kept.
- **Shot recordings**: every shot keeps its video and tracking data. Play it back with the tracer, or share it as a zip for later analysis. Recordings are kept for up to 32 shots.
- **Distance and ball locator**: GPS tee and landing pins give the shot distance, and the launch on camera gives an estimate of where the ball came down.
- **Shot history**: snapshots with the tracer, club and distance stats, and CSV export.

See the **[User Manual](USER_MANUAL.md)** for setup, how to record shots, and every feature in detail.

## Building

Android (Kotlin, Jetpack Compose, CameraX), minimum Android 8.0.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/golfballtracer-debug.apk`. Unit tests run with `./gradlew testDebugUnitTest`. Saved shot recordings can be replayed with `ShotReplayTest` (see the manual).
