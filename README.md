# Akademie Auto

Android Auto app that shows the pur-life Akademie (starting at the Heilpraktiker-Ausbildung 1 course, https://pur-life.de/akademie/kurs/42) or YouTube on the car screen and plays its
videos, parked or driving (intended for passengers).

## How it works

- Registers as an Android Auto **POI** app with `MapWithContentTemplate`, which gives the app its own drawing
  surface (a "map") without being a navigation app, so Google Maps keeps navigating alongside it.
- Renders a normal `WebView` into a private virtual display that is mirrored onto that surface
  (`WebSurfaceRenderer` / `CarPresentation`). The car page follows the phone (`CarSync`): same page, and a
  video put into fullscreen on the phone continues fullscreen in the car.
- Taps on the car screen are forwarded to the page; dragging scrolls. Buttons: Skip Silence and the
  Akademie/YouTube switch, drawn by the app itself (no Android Auto button strip).
- The template always shows a content card top left (it can't be removed, made smaller or transparent, in any
  car app category). The page therefore starts below a slim top band holding the empty card and the buttons, so
  nothing of it is covered. A fullscreen video uses the whole screen; the buttons fade out after 4 s and the
  next tap brings them back.
- The phone app has the same Akademie/YouTube switch (bottom left). It keeps the status bar and
  navigation buttons visible, hiding them only while a video is in fullscreen.
- Vimeo players load only once they're on screen (a course page mounts dozens, which can exhaust the
  phone's video decoders). Each gets a "Direkt öffnen" button that opens the player as its own page.
- Pages always see themselves as visible, and a foreground service keeps the app alive, so YouTube and
  Vimeo keep playing in the background / with the screen off.
- Pull down on the phone to reload the page.
- Media notification / lock-screen controls (title, play/pause, −10 s / +30 s, seek bar) for whatever plays in any
  page or embedded player; also reacts to headset and steering-wheel media buttons.
- **Skip Silence**: the [Skip Silence](https://github.com/vantezzen/skip-silence) browser extension (MIT) runs in
  every page and embedded player. Its settings popup opens from the "⏩ Skip Silence" button (phone) or the ⏩ button
  (car). Source is vendored in `skip-silence/upstream`; `skip-silence/android` replaces the extension APIs with
  the app's `AASkip` bridge. Rebuild the bundles in `app/src/main/assets/skipsilence` with `skip-silence/build.sh`.
- Troubleshooting: long-press the switch button on the phone to view/share a log of page errors.
- **Log in once in the phone app** (there's no keyboard on the car). Cookies are shared, so the car
  side is logged in too.
- Audio plays through the car speakers like any media app.

## Installing

Android Auto hides apps that weren't installed from the Play Store, so there are two options:

### A) Sideload (Akademie Video only)
Android Auto rejects sideloaded template car apps ("Akademie Web") in real cars; it only allows them with
Google's Desktop Head Unit emulator. Sideloaded media apps are allowed, so the car shows "Akademie Video":
the lesson's sound plus a still of the video as album art (refreshed every second, also while driving).
Pick the lesson on the phone; the zoom button enlarges the picture, and the phone's "Auto-Text aus" button
removes the title from the car screen.
1. Download `akademie-auto-debug-apk` from the latest GitHub Actions run and install it on the phone.
2. Android Auto settings → tap **Version** ~10× → developer mode on → ⋮ → **Developer settings** →
   enable **Unknown sources**.
3. In Android Auto → Customise launcher, enable "Akademie Auto".

### B) Play Console internal testing
Easiest: `bash scripts/play-setup.sh` walks through all of the steps below (key, secrets, CI build, Play
Console, phone) and checks each one. By hand:
1. Create an upload keystore:
   `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`
2. Add these repository secrets: `SIGNING_KEYSTORE_BASE64` (`base64 -w0 release.jks`),
   `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
3. Create the app in Play Console (package `de.dsalex.akademieauto`) and opt in to Android Auto
   (Test and release → Advanced settings → Form factors → add Android Auto). Upload the **first** AAB by hand
   from the `akademie-auto-release-aab` artifact to Testing → Internal testing, and add testers' emails.
4. Optional automation: create a service account with release permissions, store its JSON key as
   `PLAY_SERVICE_ACCOUNT_JSON`, then run the workflow manually with "Upload to Play" checked.
5. Testers accept the opt-in link and install from Play; the app then shows up in Android Auto.

Note: Google doesn't review internal-testing builds for Android Auto's quality rules. Closed, open
and production tracks would be rejected (this isn't a real POI app, and video isn't allowed).
