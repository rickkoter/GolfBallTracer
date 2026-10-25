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
| (■ Stop listening)        (Locate Ball)   ( Review ) |
+------------------------------------------------------+
```

### Recording Shots (Hands-Free)
There's nothing to tap before a shot. While the Tracer View is open, the app **listens for the strike and watches the ball**:

1. **Set up the phone** so the ball on the tee (or mat) is in view, ideally on a tripod or leaning against your bag behind the golfer. The screen stays on while the app is open (it can't hear or see shots with the screen off), so plug in for long sessions. The app opens **not listening**: tap **Start listening** (bottom left) when you're ready. The banner then shows **👂 Listening for shot sound**, plus whether the mic and video are on and how many shot recordings are stored.
2. **Choose the golfer and club** (optional): on the card at the top, tap **GOLFER 1** to pick golfer 1 to 4, and the club badge to pick the club. Each shot is tagged with both, and they stay as chosen until you change them, even when switching to the Shot Log and back.
3. **Just hit.** The crack of the club on the ball, together with the ball vanishing from where it sat, starts the shot. A ring marks the ball that was hit and the banner turns red (**🔴 Shot detected!**) while the flight is followed for 3 seconds. That ends by itself: there's no need to tap the screen.
   - A practice swing, a clap or a shot from the next bay doesn't start anything: it takes both the sound and the ball leaving.
   - The microphone is what hears the strike. It's on whenever the app starts; **⚙ Settings › Hear the strike** turns it off, or asks for permission if it isn't allowed yet. While it's on, a little bar in the banner shows how loud it is, so you can see it's hearing.
   - Each sharp sound that doesn't start a shot is noted in the banner, e.g. "heard a strike (+24 dB) during a swing • no ball seen leaving". The frames from those few seconds are saved to `Downloads/GolfBallTracer/missed-strike_*.zip` (at most one every 15 seconds) so missed shots can be worked out later.
   - With the microphone off, the app falls back to watching for motion alone, which misses more shots and picks up things that aren't shots.
   - A phone held in the hand works, even if it drifts or tilts during the swing, though a tripod or a steady lean still gives the longest tracers. Filming from close behind with the ball right at the bottom of the picture works too.
   - Strikes heard while the phone points at the ground (carried, or while balls are raked over) aren't taken as shots.
   - If shot detection ever falls behind the camera (a slow phone, or a lot going on), the banner says **⏳ Shot detection is … s behind the camera** until it catches up.
4. **The tracer appears** over a still from just after the strike, and the shot is **saved to your Shot Log automatically**, along with its video. When the ball is only visible for a moment (a small, distant ball lost against trees or sky), the tracer is **estimated**: the part where the ball was actually seen is drawn solid, and the rest of the flight, worked out from how fast and which way it left the tee, is drawn **dashed**. A few seconds later the app traces the shot again from the full-resolution, full-frame-rate video. If that trace follows the ball at least as far, it replaces the first one. The banner says which was used.
   - **Is the shot worth keeping?** When the ball's flight can't be trusted, the app asks you to **Keep** or **Discard** the shot. It asks when part of the shot wasn't recorded (for example the video started after the strike, or camera frames were missed during the flight), and when a strike was heard but no ball was seen flying (often not a real shot). Discarding removes the shot with its recording and snapshot.
5. **Listening stops after each shot.** The traced shot stays on screen with **Locate Ball** (an arrow and the distance in yards to where it should have landed) and **Review**. Change the golfer or club for the next shot if you need to, then tap **Start listening** when the next golfer is ready. Pinning the ball's GPS (the navigation button on the top card) once you reach it saves the measured distance into that shot.
6. **Stop listening / Start listening** (bottom left) is the one switch for listening. Stop it while walking with the phone or between sessions: a shot being followed is dropped, the camera, microphone and video stop, and a shot already on screen stays so you can review it. **Start listening** always starts afresh: it clears the screen and listens to what the camera sees from that moment. During a debug recording the switch is replaced by **Stop debug**.

---

## 2a. Shot Recordings

Each shot keeps a **recording**: the video from just before the swing to the end of the flight, plus the tracking data (where the ball was, when it was struck, every spot the detector saw, and both tracers).

- **Watch it**: the Shot Review screen plays the video on a loop with the tracer growing along with the ball. Tap the video to pause, or switch to the snapshot.
- **Share it for analysis**: tap the 🎥 icon on Shot Review to share that shot's recording as one zip, or the 🎥 icon in the Shot Log to share every recording. Pick Google Drive (or email, etc.) in the share sheet. The zips can be replayed on a computer with `ShotReplayTest` (copy them into `app/shots/`).
- **Limit**: the phone keeps recordings for up to **32 shots**. When that's reached, listening stops and the app offers to **remove the recordings of the earliest 18 shots**. Those shots stay in your history with their snapshots and distances. Share any you want to keep first. Until you make room, no new shots are recorded; tap the banner to bring the offer back.
- Diagnostic frames for each shot are also saved to `Downloads/GolfBallTracer/` as before.

### Debug Recording
For working out why shots are missed: **Settings › Start debug recording** records up to 45 seconds of everything (the video, the microphone's sound, every frame the shot detector looks at, and each decision it makes) whether or not a shot is found. Swing and hit as usual while it runs; the banner shows the time, and **Stop debug** ends it early. Shots found meanwhile are noted but not saved to your history. After each one a note box appears, so you can say what happened (for example "topped it" or "phone moved"); it's optional, so leave it and carry on, or tap **Skip**. **Add note** writes a note about any other moment. Notes go into the recording's log next to the shot, and a note still being typed is kept when you tap Stop debug. The recording is saved to `Downloads/GolfBallTracer/debug-session_*.zip` (roughly 100–200 MB) and can be replayed on a computer with `DebugSessionReplayTest`, which also shows how close missed strikes came to being heard.

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
- **Export the video**: tap the 🎬 (film) icon to make the shot's video with the tracer drawn in, growing along with the ball as on the review, plus a caption with the club, golfer and distance. It's cropped to the view the shot was traced in and keeps the sound of the strike. Making it takes a few seconds (the icon shows how far along it is); it's then saved to the phone's **Movies/GolfTracker** folder, where the Photos app finds it, and **Share** on the message that appears sends it on. The icon only shows for a shot that still has its video.

---

## 6. Shot History & Performance Analytics

Tap the **Shot Log** tab on the bottom navigation bar to access your saved shots gallery:

- **Performance Summary**: View your Total Shots recorded, Average Shot Distance, and Longest Drive across all clubs.
- **Shot Log List**: Inspect thumbnail previews, dates, club and golfer tags, and yardages for every recorded shot. Distances are in yards: measured on GPS for a shot whose ball you pinned, or estimated from the launch (shown with a ~ and "estimated"). A shot with neither shows "No distance".
- **Find a past shot's ball**: tap the locate (crosshair) icon on a shot, or **Locate Ball** on its review, to open the ball locator for it, pointing the way from where you are now. It uses the landing estimate saved with the shot, or, for a shot whose ball you pinned on GPS, that spot. Shots with neither (including those from before this was added) have the icon greyed out.
- **Delete Shots**: Tap the trash icon next to any shot to remove it, and its recording, from history.
- **Export several videos**: press and hold a shot to start selecting, then tap more shots (or **Select all** at the top). Tap the 🎬 icon to export each selected shot's video with its tracer, one video per shot, to **Movies/GolfTracker**; **Share** on the message that follows sends them all at once. Shots without a video are left out and counted in the message. Tap ✕ or Back to stop selecting.
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
5. **Frame Rate**:
   - On phones whose camera films 60 frames a second, the app analyses all 60: the ball's first moments are seen in twice as many frames, which finds the ball and its flight more often. If the phone can't keep up, it drops to 30 by itself and tries 60 again later (debug recordings log each change as `analysis-rate`). Debug recordings at 60 fps are about twice the size.

---

*Enjoy your round and hit 'em straight! ⛳*
