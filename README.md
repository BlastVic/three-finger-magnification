# Three-Finger Magnification (Triple-Tap Zoom)

[中文说明](README.zh-CN.md) · [Download v1.0](https://github.com/BlastVic/three-finger-magnification/releases/tag/v1.0) · [MIT License](LICENSE)

> Bring the iOS-style **double-tap with three fingers to zoom** gesture to Android — system-wide, including inside games — **without changing any other touch behaviour**.

Built by **Vic Yang**. Free and open source, for anyone who is annoyed by Android's stock magnification shortcut.

---

## Why this exists

Android's built-in **"Triple-tap to zoom"** shortcut reacts to three quick taps with **one finger**. That is exactly what you do in a rhythm game, a shooter, or when double-tapping a link twice — so the screen zooms in by accident, usually at the worst possible moment.

iOS solves this with a **double tap using three fingers simultaneously**: a gesture that essentially never happens by accident. This app reproduces it on Android.

Three design goals, in this order:

1. **Works everywhere** — home screen, browser, video player, full-screen games.
2. **Only that one gesture is handled.** Single taps, long presses, swipes, edge-back, the bottom home gesture and the notification shade must behave *exactly* as they do without this app. No buffering, no interception, no injected events.
3. **No root, no network, no data collection.**

> The first prototype used Android's accessibility touch-interception APIs. It did detect the gesture, but it also broke the bottom swipe-home gesture, turned icon taps into phantom long presses and produced random "held" touches. That approach was abandoned. The version published here reads touch events **read-only** instead.

---

## Features

| | |
|---|---|
| **Trigger** | Double tap with **three fingers**, anywhere on screen |
| **Toggle** | Repeat the same gesture to restore 1.0× |
| **Zoom centre** | At the three fingers (iOS-like, default) or fixed screen centre |
| **Zoom level** | 1.5× – 5.0×, default 2.0× |
| **While zoomed** | Normal taps still work; drag with two fingers to pan |
| **Coverage** | System-wide, including full-screen games and immersive apps |
| **Extras** | Haptic feedback, keep zoom across app switches, Quick Settings tile, on-screen gesture diagnostics log |
| **Language** | English by default; switches to Chinese automatically on Chinese system locales |
| **Privacy** | No `INTERNET` permission, no screen-content reading, no analytics, logs stay on device |

---

## Why Shizuku is required

An `AccessibilityService` cannot observe raw touches without enabling **touch exploration**. Touch exploration rewrites single-finger semantics: a tap becomes a two-step "explore then activate" action, long presses appear out of nowhere, and system gesture navigation stops working. Many vendor ROMs also refuse to deliver passthrough event copies for multi-finger gestures. In other words, with the public accessibility API you can have **either** zero interference **or** a copy of the touch stream — not both.

So this app uses [Shizuku](https://shizuku.rikka.app/):

- Shizuku starts a small process with **shell (ADB) privileges** (no root needed).
- That process opens the touchscreen's `/dev/input/eventX` node and **only reads** it. The kernel keeps delivering the very same events to apps and SystemUI, untouched.
- A tiny state machine recognises a three-finger double tap and notifies the app.
- The accessibility service then toggles zoom through Android's official `MagnificationController`.

**Nothing is injected, consumed, intercepted or buffered.** Shizuku is used purely as a read-only sensor. This is also why the magnifier itself still needs the accessibility service.

The trade-off: on non-rooted devices, **Shizuku must be started again after every reboot** (an Android limitation, see the [Shizuku user manual](https://shizuku.rikka.app/guide/setup/)).

---

## Requirements

- Android **11 (API 30)** or later with a real touchscreen
- [Shizuku](https://shizuku.rikka.app/) **installed and running** (v11.2.0+, tested with 13.6.0)
- Accessibility permission for this app
- ~100 KB, no account, no sign-in

---

## Setup, step by step

### 1. Install the APK

Download [`three-finger-magnification-v1.0.apk`](https://github.com/BlastVic/three-finger-magnification/releases/tag/v1.0) from Releases and install it.

> Installing over an earlier test build signed with a different key will fail with a signature conflict. Uninstall the old build first.

### 2. Install and start Shizuku

Install Shizuku from the [official site](https://shizuku.rikka.app/) or its [GitHub releases](https://github.com/RikkaApps/Shizuku/releases). Then start it using **one** of these methods:

**A. Rooted device** — open Shizuku, tap **Start via root**. Done.

**B. No root, Android 11+ (recommended, no computer needed)**

1. Enable **Developer options** and **USB debugging**, then open **Wireless debugging** and turn it on.
2. In Shizuku, tap **Pairing** → in Wireless debugging tap **Pair device with pairing code**.
3. Enter the 6-digit code into Shizuku's notification (switch MIUI notification style to "Android" if this fails immediately).
4. Back in Shizuku, tap **Start**. If nothing happens, toggle Wireless debugging off and on.
5. Pairing is one-time, but **starting must be repeated after every reboot**.

**C. No root, with a computer (Android 10 and below, or any version)**

Enable USB debugging, authorise your computer, then run:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

This is the official command for Shizuku v11.2.0+. A ready-to-use helper is in [`tools/start-shizuku-adb.sh`](tools/start-shizuku-adb.sh). It also has to be repeated after every reboot.

**Vendor quirks** (from the [Shizuku FAQ](https://shizuku.rikka.app/guide/setup/#faq)): allow Shizuku to run in the background; on MIUI also enable **USB debugging (Security options)**; on ColorOS disable **Permission monitoring**; on Flyme disable **Flyme payment protection**. If Shizuku keeps stopping, set the USB mode to **Charge only / No data transfer** and keep Developer options enabled.

### 3. Authorise this app in Shizuku

Open **Triple-Tap Zoom**. A Shizuku permission dialog appears — choose **Always allow**. You can verify under Shizuku → *Authorised apps*. (If you denied it, reopen the app or re-authorise from Shizuku.)

### 4. Enable the accessibility service

Open the app and tap **Enable accessibility service** → find **Triple-Tap Zoom** in Settings → switch it **on** and accept the requested capabilities (touch exploration + magnification).

Or manually: **Settings → Accessibility → Installed services / Downloaded services → Triple-Tap Zoom → On**.

> On some ROMs (notably vivo/OriginOS) an app update silently unbinds the accessibility service. If the gesture stops working after an update, toggle the service off and on again.

### 5. Turn OFF the stock triple-tap zoom

**Settings → Accessibility → Magnification → Triple-tap to zoom → Off.**

Otherwise the stock single-finger shortcut will still fire while you play. The app has a **Disable system triple-tap zoom** button that jumps to the right page.

### 6. Verify

Back in the app, the status should read:

```
● Service running · Zero interference (no touch interception)
Shizuku: Listening (raw touch, read-only)
```

Now **double tap with three fingers**. The diagnostics panel should show `★ Three-finger double tap` followed by `→ Zoom 2.0×`. Repeat the gesture to restore `→ Zoom off`.

Optionally tap **Background / battery optimization** and set this app (and Shizuku) to *Unrestricted*, so an aggressive freezer does not kill the listener.

---

## Using it

- Place **three fingers** on the screen and lift, then do it again — the two taps should be within about **1.1 seconds**.
- Keep the fingers still and make each tap short (**under 600 ms**). They do not have to land perfectly at the same instant; small jitter is tolerated.
- **Repeat** the same gesture to turn zoom off.
- **Two-finger drag** pans the magnified view. Single taps still work while zoomed.
- Adjust zoom level, centre mode, vibration and "keep zoom when switching apps" in the app.
- A **Quick Settings tile** is available for a quick on/off of the feature.
- **Keep "Zero-interference mode" ON.** The legacy "takeover mode" switch is kept only as a fallback for ROMs that do deliver passthrough copies; it briefly buffers touches and can cause phantom long presses. Do not use it for daily use.

---

## Troubleshooting

| Symptom | What to check |
|---|---|
| Nothing happens at all | Is Shizuku **running**? App status should say *Listening*. After a reboot, start Shizuku again. |
| `Service not running (start it in Shizuku)` | Start Shizuku (step 2), then reopen this app. |
| `Waiting for Shizuku authorisation` / `Authorisation denied` | Grant the permission in Shizuku, choose *Always allow*. |
| `Listener failed to start, code=2` | The shell cannot open the touch node on this ROM. Check that Shizuku really runs with ADB/shell privileges (not limited by an OEM restriction). |
| Zoom never triggers but the log shows `round# … peak=3` | Taps too slow, fingers sliding, or the two taps more than 1.1 s apart. Tap crisply and keep fingers still. |
| Stock zoom still fires on three quick taps | Turn off *Accessibility → Magnification → Triple-tap to zoom* (step 5). |
| App update broke the gesture | Re-enable the accessibility service; on vivo also launch the app once from the launcher before re-enabling. |
| Status briefly says *connecting / UserService disconnected* | Reconnection is asynchronous; wait a couple of seconds. Trust the diagnostics log line *Listening*. |
| Bottom swipe-home / long press behaves oddly | Make sure *Zero-interference mode* is ON. Never leave takeover mode enabled. |
| Works in the browser / launcher, but **not in Settings, WeChat or banking apps** | Some ROMs (notably vivo / OriginOS on Android 15+) **suspend third-party accessibility services while a "protected" app is in the foreground**: `settings get secure accessibility_enabled` drops to `0` and all third-party services are unbound, so `MagnificationController` is unavailable. The gesture is still detected (Shizuku reads evdev), but the app cannot apply zoom. The service rebinds automatically ~6–10 s after you leave that app — no action needed. This is an OS security policy, not an app bug, and most vivo builds expose no switch to turn it off. **"Restricted settings" is a different problem**: it only stops the accessibility switch from being turned on at all (a "Restricted settings" dialog appears). On vivo the path is *Settings → Apps → App management → find Triple-Tap Zoom → App info → ⋮ / More → Allow restricted settings*, or tap *Unrestrict* in that dialog (vivo [explains it here](https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1836); owner verification may be required). The ⋮ menu and the dialog only appear while the app is actually restricted — if they are absent, the app is not restricted, so do not keep looking for them. |

For deeper debugging, everything is logged to logcat under the tags `ZoomTapLog`, `ZoomTapReader` and `ZoomTapBridge`.

---

## How it works (for developers)

```
/dev/input/eventX  ──read-only──>  Shizuku UserService (uid shell)
                                     │  parse 24-byte input_event (little endian)
                                     │  ABS_MT_SLOT / TRACKING_ID / POSITION_X,Y
                                     │  per-finger movement check, peak >= 3
                                     │  short tap <= 600 ms, two taps <= 1100 ms
                                     ▼
                     Binder callback + explicit broadcast (same unique id)
                                     ▼
                     TripleTapReceiver (de-duplicates by id)
                                     ▼
                     ZoomAccessibilityService
                       MagnificationController.setScale / setCenter
```

Key implementation details:

- The touch node is auto-detected with `getevent -p` (first node exposing `ABS_MT_TRACKING_ID` / `0039` plus X and Y axes); `/dev/input/event5` is only a fallback.
- Each finger is compared against **its own** down position (6% slop) — comparing against the first active slot produced false "moved" results when slots were released.
- A rejected three-finger round does **not** clear the pending first tap, because fingers rarely land simultaneously.
- The shell side delivers the event over **two channels**: a Binder callback and an explicit `am broadcast` with `FLAG_INCLUDE_STOPPED_PACKAGES`. The broadcast survives OEM process freezers that kill Binder endpoints. Both carry the same id, so the app de-duplicates by id rather than by a time cooldown (a cooldown used to swallow genuine rapid gestures).
- The accessibility config requests `canControlMagnification` and touch exploration, but in zero-interference mode the touch stream is never taken over.
- The shell process cannot read app resources, so the device language is passed through `ITouchReader.start()` to keep shell diagnostics in the same language as the UI.

Source layout:

```
app/AndroidManifest.xml
app/aidl/          AIDL contracts (ITouchReader, ITouchCallback)
app/src/           Java sources (hand-written Binder Stub/Proxy)
app/res/           values/ (English default) + values-zh/ (Chinese)
app/libs/          Shizuku 13.1.5 classes (see THIRD_PARTY_NOTICES.md)
tools/             optional adb helper
build.sh           aapt2 -> javac -> d8 -> zipalign -> apksigner
```

---

## Build from source

No Gradle and no Android Studio project are needed.

**Requirements:** JDK 17, Android SDK platform `android-35`, build-tools `36.0.0`, and bash (Git Bash on Windows). Point `ANDROID_HOME` and `JAVA_HOME` at your installs if they are not in the default locations; `BUILD_TOOLS_VERSION` and `ANDROID_PLATFORM` can be overridden too.

```bash
git clone https://github.com/BlastVic/three-finger-magnification.git
cd three-finger-magnification
bash build.sh
# -> dist/three-finger-magnification-v1.0.apk (local debug key, for testing only)
```

To sign a distributable APK with your own key:

```bash
SIGN_KEYSTORE=/path/to/your.keystore \
SIGN_STORE_PASS='store-password' \
SIGN_KEY_ALIAS=your-alias \
SIGN_KEY_PASS='key-password' \
bash build.sh
```

**Never commit your keystore or passwords.** The published releases are signed with Vic Yang's key; APKs built from source with your own key cannot be installed as an update over a release build (Android rejects mismatched signatures) — uninstall first.

The AIDL stubs in `app/src` are hand-written because `aidl.exe` on Windows mishandles some path forms; `app/aidl` documents the interfaces.

---

## Privacy

The app declares only `VIBRATE` and the Shizuku API permission. It has **no network permission**, does not read screen content, collects nothing, and stores its diagnostics log locally in app-private storage. The raw touch stream is read in memory by the shell process and never written to disk or sent anywhere.

---

## Known limitations

- Non-rooted devices: Shizuku must be restarted after every reboot.
- On ROMs that protect sensitive screens (vivo/OriginOS, some banking or payment apps), the system may unbind third-party accessibility services while **Settings, WeChat or similar apps are in the foreground**. Magnification cannot be toggled on those screens; it works again a few seconds after leaving them. This runtime protection is an OS policy and cannot be disabled by the app. Android 15 "restricted settings" is unrelated: it is a one-time install-source gate that only blocks *enabling* the accessibility switch, and does not cause the temporary unbind described above.
- Heavily locked ROMs may forbid the shell user from reading `/dev/input`, or may freeze the app process; the broadcast fallback handles the latter.
- Secondary/external touch panels and some foldables may report a different evdev node; auto-detection picks the first suitable one.
- Magnification is Android's own full-screen magnifier, so apps that render via unusual surfaces may look different while zoomed.
- Some Android 15 builds reset zoom when switching apps; the "keep zoom" option restores it automatically.
- This is an accessibility tool, not a general input remapper. It intentionally refuses to touch any gesture other than the three-finger double tap.

---

## Contributing

Issues and pull requests are welcome — especially device compatibility reports (please include your device model, Android version, Shizuku version and the relevant `ZoomTapLog` lines). If you found this useful, a star helps other people with the same problem find it.

## Credits & license

- **Author:** Vic Yang
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — Apache-2.0, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Android Open Source Project for the magnification and accessibility APIs

This project is released under the [MIT License](LICENSE).

> This app is provided as-is, without warranty. It needs elevated local privileges (Shizuku + accessibility) to do its job; review the source before installing if that matters to you.
