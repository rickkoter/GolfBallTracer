# ⛳ GolfBallTracer - User Manual & Golfer's Guide

Welcome to **GolfBallTracer** for Android! This application transforms your smartphone camera into a PGA Tour-style shot tracer system. It tracks your ball's launch trajectory in real time, overlays luminous glowing flight arcs, calculates exact shot distance using GPS location telemetry, and exports high-definition snapshot telemetry cards to share with friends.

---

## 📋 Table of Contents
1. [First-Time Setup & Permissions](#1-first-time-setup--permissions)
2. [Using the Live Viewfinder & Tracer](#2-using-the-live-viewfinder--tracer)
3. [Tracer Visual Styles & Apex Tuning](#3-tracer-visual-styles--apex-tuning)
4. [GPS Distance & Telemetry Guide](#4-gps-distance--telemetry-guide)
5. [Shot Review & Social Sharing](#5-shot-review--social-sharing)
6. [Shot History & Performance Analytics](#6-shot-history--performance-analytics)
7. [Pro Tips for Maximum Tracking Accuracy](#7-pro-tips-for-maximum-tracking-accuracy)

---

## 1. First-Time Setup & Permissions

When launching the app for the first time, grant the following permissions when prompted:

- **Camera**: Required to display the live viewfinder, analyze ball launch frames and record each shot's video.
- **Microphone**: Used only to hear the club strike the ball, which starts a shot. No sound is recorded or saved.
- **Location (Precise GPS)**: Required to calculate exact shot distance between your tee launch point and ball landing position.
- **Photos / Media Storage**: Required to save HD tracer snapshot images to your phone's photo gallery (`Pictures/GolfTracker`).

---

## 2. Using the Live Viewfinder & Tracer

The app opens directly to the **Tracer View** camera screen.

```
+------------------------------------------------------+
| [Flash]                  [Pro Lime v]   [Tune Curve] |
|                                                      |
|                     ( Camera View )                  |
|                                                      |
|                     /---*---.                        |
|                    /   Apex  \                       |
|                   /           \                      |
|                  o Tee         (O) Landing Pin       |
|                                                      |
| +--------------------------------------------------+ |
| | ⚡ GOLF TRACER PRO                  [ DRIVER v ]  | |
| | SHOT DISTANCE: 275 YDS (251 m)                   | |
| | [ Tee Locked ]                 [ Pin Ball GPS ]  | |
| +--------------------------------------------------+ |
|        (Reset)       (( RECORD SHOT ))     (Simulate)|
+------------------------------------------------------+
```

### Recording Shots (Hands-Free)
There's nothing to tap before a shot. While the Tracer View is open, the app **listens for the strike and watches the ball**:

1. **Set up the phone** so the ball on the tee (or mat) is in view, ideally on a tripod or leaning against your bag behind the golfer. The screen stays on while the app is open (it can't hear or see shots with the screen off), so plug in for long sessions. The banner shows **👂 Listening**, plus whether the mic and video are on and how many shot recordings are stored.
2. **Select Your Club** (optional): tap the club badge on the telemetry card.
3. **Just hit.** The crack of the club on the ball, together with the ball vanishing from where it sat, starts the shot. A ring marks the ball that was hit and the banner turns red (**🔴 Shot detected!**) while the flight is followed for a few seconds. Tap the screen to finish early once the ball lands.
   - A practice swing, a clap or a shot from the next bay doesn't start anything: it takes both the sound and the ball leaving.
   - The **🎤 button** (bottom left) turns listening for the strike on and off. It's on whenever the app starts. If it's gold, the microphone isn't allowed yet: tap it to allow it. While it's on, a little bar in the banner shows how loud it is, so you can see it's hearing.
   - Each sharp sound that doesn't start a shot is noted in the banner, e.g. "heard a strike (+24 dB) during a swing • no ball seen leaving". The frames from those few seconds are saved to `Downloads/GolfBallTracer/missed-strike_*.zip` (at most one every 15 seconds) so missed shots can be worked out later.
   - With the microphone off, the app falls back to watching for motion alone, which misses more shots and picks up things that aren't shots.
4. **The tracer appears** over a still from just after the strike, and the shot is **saved to your Shot Log automatically**, along with its video. A few seconds later the app traces the shot again from the full-resolution, full-frame-rate video. If that trace follows the ball at least as far, it replaces the first one. The banner says which was used.
5. **Keep hitting.** The app is already listening for the next shot. Tap **Review** to open the shot, **Locate Ball** to find where it landed, ↻ to clear the screen, ⏸ to stop listening (for example while walking with the phone), or 🎤 to turn the microphone off.

---

## 2a. Shot Recordings

Each shot keeps a **recording**: the video from just before the swing to the end of the flight, plus the tracking data (where the ball was, when it was struck, every spot the detector saw, and both tracers).

- **Watch it**: the Shot Review screen plays the video on a loop with the tracer growing along with the ball. Tap the video to pause, or switch to the snapshot.
- **Share it for analysis**: tap the 🎥 icon on Shot Review to share that shot's recording as one zip, or the 🎥 icon in the Shot Log to share every recording. Pick Google Drive (or email, etc.) in the share sheet. The zips can be replayed on a computer with `ShotReplayTest` (copy them into `app/shots/`).
- **Limit**: the phone keeps recordings for up to **32 shots**. When that's reached, listening stops and the app offers to **remove the recordings of the earliest 18 shots**. Those shots stay in your history with their snapshots and distances. Share any you want to keep first. Until you make room, no new shots are recorded; tap the banner to bring the offer back.
- Diagnostic frames for each shot are also saved to `Downloads/GolfBallTracer/` as before.

### Debug Recording
For working out why shots are missed: **Settings › Start debug recording** records up to 45 seconds of everything (the video, the microphone's sound, every frame the shot detector looks at, and each decision it makes) whether or not a shot is found. Swing and hit as usual while it runs; the banner shows the time, and **Stop debug** ends it early. Shots found meanwhile are noted but not saved to your history. The recording is saved to `Downloads/GolfBallTracer/debug-session_*.zip` (roughly 100–200 MB) and can be replayed on a computer with `DebugSessionReplayTest`, which also shows how close missed strikes came to being heard.

---

## 3. Tracer Visual Styles & Apex Tuning

### Customizing Tracer Colors:
Tap the **Tracer Style Badge** at the top right of the camera screen to choose your favorite glowing tracer palette:
- **Pro Lime** (Classic PGA Tour neon green glow)
- **Cyan Spark** (Electric cyber blue)
- **Fire Red** (High-visibility orange/red flame)
- **Gold Glow** (Championship gold spark)
- **Neon Purple** (Futuristic laser violet)

### Manual Apex Curve Tuner:
If cloudy weather or extreme sun glare makes tracking difficult:
1. Tap the **Pencil Icon** (Tune Curve) at the top right corner.
2. Drag your finger across the camera screen to adjust the arc's height, apex curvature, or landing target pin location.
3. Tap the **Simulate Button** (magic wand icon) for an instant aerodynamic trajectory model.

---

## 4. GPS Distance & Telemetry Guide

The app features two distance estimation modes:

### Mode A: Dual-Point GPS Pinning (Most Accurate)
1. **At the Tee**: Tap **Set Tee GPS** before swinging.
2. **Walk/Drive to Your Ball**: Walk to where your ball landed on the fairway or green.
3. **At the Ball**: Tap **Pin Ball GPS**. The app calculates the exact **Haversine distance in Yards and Meters** between your tee location and landing position, accounting for elevation changes.

### Mode B: Optical Telemetry Estimator (Standing at Tee)
If you stay at the tee box, the app estimates shot distance using camera launch angle, camera elevation tilt, and standard aerodynamic drag formulas for dimpled golf balls.

---

## 5. Shot Review & Social Sharing

After completing a shot, you are automatically taken to the **Shot Review** screen:

- **HD Snapshot Preview**: Displays the captured camera frame, glowing vector flight arc, and the **⚡ GOLF TRACER PRO** telemetry badge.
- **Telemetry Card**: Displays Total Distance (Yards & Meters), Club used, Timestamp, and GPS coordinates.
- **Share Button**: Tap the **Share Icon** in the top right header to instantly send your snapshot image to Instagram, Messages, WhatsApp, Twitter/X, or Email.

---

## 6. Shot History & Performance Analytics

Tap the **Shot Log** tab on the bottom navigation bar to access your saved shots gallery:

- **Performance Summary**: View your Total Shots recorded, Average Shot Distance, and Longest Drive across all clubs.
- **Shot Log List**: Inspect thumbnail previews, dates, club tags, and yardages for every recorded shot.
- **Delete Shots**: Tap the trash icon next to any shot to remove it, and its recording, from history.
- **Recordings**: Shots with a 🎥 have a recording. The summary shows how many of the 32 are used.

---

## 7. Pro Tips for Maximum Tracking Accuracy

1. **Camera Placement**:
   - For best results, place your phone on a tripod or lean it against a golf bag **3 to 6 feet directly behind the golfer** aligned with the target line.
2. **Lighting Conditions**:
   - Positioning the camera facing away from direct sunlight gives optimal contrast against green fairways and blue sky.
3. **Golf Ball Visibility**:
   - Standard white golf balls and hi-vis neon yellow/green balls provide the best real-time optical luminance tracking.
4. **Keeping Lenses Clean**:
   - Wipe your phone camera lens clean before a round to ensure crisp frame analysis.

---

*Enjoy your round and hit 'em straight! ⛳*
