# Akademie Auto

Android Auto app that shows https://pur-life.de/akademie (or YouTube) on the car screen and plays its
videos, parked or driving (intended for passengers).

## How it works

- Registers as an Android Auto **navigation** app – the only app category that gets a raw drawing
  surface on the car screen.
- Renders a normal `WebView` into a private virtual display that is mirrored onto that surface
  (`WebSurfaceRenderer` / `CarPresentation`).
- Taps on the car screen are forwarded to the page. Buttons on the right: back, home, reload and a
  switch between Akademie and YouTube; map buttons: pan mode (drag to scroll), page up/down and
  fullscreen (blows the largest video on the page up to the whole screen; tap again to restore).
  The players' own fullscreen buttons work too.
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
  every page and embedded player. Its settings popup opens from the "⏩ Skip Silence" button (phone) or the ⏩ action
  (car). Source is vendored in `skip-silence/upstream`; `skip-silence/android` replaces the extension APIs with
  the app's `AASkip` bridge. Rebuild the bundles in `app/src/main/assets/skipsilence` with `skip-silence/build.sh`.
- Troubleshooting: long-press the switch button on the phone to view/share a log of page errors.
- **Log in once in the phone app** (there's no keyboard on the car). Cookies are shared, so the car
  side is logged in too.
- Audio plays through the car speakers like any media app.

## Installing

Android Auto hides apps that weren't installed from the Play Store, so there are two options:

### A) Sideload (quickest, for your own phone)
1. Download `akademie-auto-debug-apk` from the latest GitHub Actions run and install it on the phone.
2. Android Auto settings → tap **Version** ~10× → developer mode on → ⋮ → **Developer settings** →
   enable **Unknown sources**.
3. In Android Auto → Customise launcher, enable "Akademie Auto".

### B) Play Console internal testing
1. Create an upload keystore:
   `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`
2. Add these repository secrets: `SIGNING_KEYSTORE_BASE64` (`base64 -w0 release.jks`),
   `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
3. Create the app in Play Console (package `de.purlife.akademieauto`). Upload the **first** AAB by hand
   from the `akademie-auto-release-aab` artifact to Testing → Internal testing, and add testers' emails.
4. Optional automation: create a service account with release permissions, store its JSON key as
   `PLAY_SERVICE_ACCOUNT_JSON`, then run the workflow manually with "Upload to Play" checked.
5. Testers accept the opt-in link and install from Play; the app then shows up in Android Auto.

Note: Google doesn't review internal-testing builds for Android Auto's quality rules. Closed, open
and production tracks would be rejected (this isn't a real navigation app, and video isn't allowed).
