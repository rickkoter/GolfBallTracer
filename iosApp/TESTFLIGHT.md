# Getting the iPhone app to testers (TestFlight)

Over a USB cable, Xcode can install the app on your own iPhone or iPad with a free Apple ID. Anyone else,
or any install without the cable, goes through **TestFlight**, Apple's beta app. TestFlight needs the paid
**Apple Developer Program** ($99 a year). The Ad Hoc alternative also needs it, and also needs every
device registered by hand, so TestFlight is the way to go.

Part 1 is done once by the app's owner, part 2 for every new build, and part 3 is what to send testers.

## 1. One-time setup (owner)

1. **Join the Apple Developer Program** with the development Apple ID (the one in Xcode, not this Mac's
   iCloud account): [developer.apple.com/programs/enroll](https://developer.apple.com/programs/enroll).
   Enroll as an individual. It needs two-factor authentication on the Apple ID, and approval usually takes
   from a few minutes to two days.
2. **Point the project at the paid team.** Find its team ID at [developer.apple.com/account](https://developer.apple.com/account)
   › Membership details and put it in `iosApp/project.yml` as `DEVELOPMENT_TEAM` (10 letters and digits,
   nothing after), then run `cd iosApp && xcodegen`. An individual who joins keeps the free personal team's
   ID: here it stayed `5TLF4X4DA2`, so nothing needed changing. The Apple ID must be signed in to
   **Xcode › Settings › Accounts**. Xcode may go on calling the team "Personal Team" for a while; the
   upload still works: Xcode signs with Apple's cloud-managed distribution certificate and makes the App Store
   profile itself, so no certificate needs installing on the Mac.
3. **Register the app in App Store Connect.** Go to [appstoreconnect.apple.com](https://appstoreconnect.apple.com)
   › Apps › **+** › New App:
   - Platform: iOS. Name: anything not already on the App Store, e.g. "GolfBallTracer Beta". Testers see
     this name in TestFlight.
   - Bundle ID: `com.koterapps.golfballtracer`. If it isn't in the list, build once for a connected device
     with the new team, which registers it, or add it under Certificates, Identifiers & Profiles ›
     Identifiers. If Apple says it's taken (it was first registered to the free team), pick a new one,
     e.g. `com.koterapps.golfballtracer.app`, and change `PRODUCT_BUNDLE_IDENTIFIER` in `project.yml` to match.
   - SKU: anything, e.g. `golfballtracer`.
4. **Fill in TestFlight's test information** (App Store Connect › the app › TestFlight › Test Information):
   a feedback email, a short description, and what to test. If it asks for a privacy policy link, a
   simple page is enough: the app keeps its recordings on the phone and sends nothing anywhere.

## 2. Uploading a build (each time)

```bash
iosApp/scripts/testflight.sh
```

The script archives the app in Release, signs it for App Store Connect with the Apple ID signed in to
Xcode, and uploads it. It first checks `DEVELOPMENT_TEAM`: it stops if the ID is malformed or no Apple ID in
Xcode has that team. "No profiles for 'com.koterapps.golfballtracer'" means the team can't make App Store
profiles: a free team, or a typo in the ID. The first upload was build 202610250750 (25 Oct 2026). Each upload gets a new build number (the date and time). The version testers see is
`MARKETING_VERSION` in `project.yml` (now 0.3). The app declares that it uses no non-exempt encryption,
so there's no export-compliance question to answer.

Apple then processes the build. It shows in App Store Connect › TestFlight after 5 to 30 minutes, and you
get an email. Builds expire after 90 days.

## 3. Inviting testers

- **Internal testers** are people on your App Store Connect team (Users and Access), up to 100. They get
  each build as soon as it's processed, with no review.
- **External testers** are anyone else, up to 10,000. Create a group under TestFlight › External Testing,
  add the build, and send it for **Beta App Review**. The first build of a version is reviewed, usually
  within a day; later builds of the same version mostly go straight through. Then either:
  - add testers by email (they get an invitation), or
  - turn on a **Public Link** and send that link to anyone. A limit can be set on how many can join.

## What to send testers

> **Trying GolfBallTracer on your iPhone**
>
> 1. On your iPhone (iOS 17 or later), install **TestFlight** from the App Store.
> 2. Open the invitation email or the link I sent you **on the iPhone**, tap **Accept** (or **View in
>    TestFlight**), then **Install**. The app appears on your home screen like any other. New versions show
>    up in TestFlight; turn on Automatic Updates there to get them by themselves.
> 3. Open GolfBallTracer and allow the **camera** and **microphone**. The app films the ball leaving
>    the tee and listens for the strike. Nothing leaves your phone unless you share it.
>
> **Tracing a shot**
>
> 1. Stand the phone **upright** a few steps behind the golfer, looking down the target line, with the ball
>    in the lower part of the screen. A tripod or a phone clamp on a bag helps: the phone must stay still.
> 2. Pick the golfer and club on the card at the top, then tap **Start listening**. Allow location too: it
>    measures shots and finds the ball.
> 3. Hit. The app hears the strike, sees the ball leave, follows it for up to 3 seconds and draws the
>    tracer over a picture of the shot. The dashed part is estimated where the ball went out of sight.
> 4. **Locate Ball** points you to where it came down; at the ball, tap the pin button on the card to
>    measure the shot. **Review** opens the shot. Tap **Start listening** for the next shot.
>
> The gear has the settings: flash, the microphone, tracer colours, a demo flight, and debug recordings.
>
> **Sending shots back**
>
> Every shot is in the **Shot Log** tab. To send one, open it and tap the camera button for its recording
> (or the camera button at the top of the log for all of them), then pick Mail, AirDrop, Messages or Google
> Drive. Shots that traced badly are the most useful: add a line about what happened. To share a video with
> the tracer, tap the film button on a shot, or press and hold shots in the log to pick several. For
> anything else, take a screenshot in the app and TestFlight offers to send it as feedback, with your comments.
