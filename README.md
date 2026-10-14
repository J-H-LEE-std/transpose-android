# PocketTranspose

PocketTranspose is an Android Kotlin WebView proof-of-concept for testing whether audio from a YouTube or HTML5 `<video>/<audio>` element can be connected to a Web Audio API graph inside an app-controlled WebView, and then adjusted (gain, speed, pitch) from native Android controls — similar in spirit to the Transpose Chrome extension.

The primary question this PoC answers: **does `AudioContext.createMediaElementSource(video)` work (and stay audible) for YouTube inside Android WebView?** If YouTube fails but a plain HTML5 media page succeeds, the first PoC still counts as a success.

## Architecture

```
MainActivity (URL bar, WebView, edge-to-edge insets, fullscreen container)
 ├─ ControlSheet                   swipe-up bottom sheet: pitch/speed/gain steppers, toggles, actions
 └─ TransposeWebViewController     WebView settings, script injection, Native -> JS via evaluateJavascript()
     ├─ PocketWebChromeClient      console -> Logcat (tag PocketTransposeJS), fullscreen, onProgressChanged
     ├─ PocketWebViewClient        onPageFinished fallback injection, SPA URL updates, WebViewAssetLoader
     └─ InjectedScriptProvider     reads assets/pocket_transpose_{probe,hook}.js

assets/pocket_transpose_probe.js   Phase 1: detect <video>/<audio> only (no Web Audio)
assets/pocket_transpose_hook.js    Phase 2+: Web Audio graph, window.PocketTranspose API, pitch shifter
assets/test/media_test.html        Local HTML5 test page (generated tone + custom media URL)
```

Web Audio graph built by the hook:

```
<video>/<audio> -> MediaElementSource -> inputBus -> [pitch shifter, only when pitch != 0] -> GainNode -> destination
                                            \-> AnalyserNode (signal level / silence detection)

pitch shifter (native nodes only):
  looping 4-ch AudioBufferSource (ramp A, window A, ramp B, window B) -> ChannelSplitter
    ramp  -> GainNode(×50 ms) -> DelayNode.delayTime      (tap A / tap B)
    window -> GainNode.gain   (sin² crossfade, A + B = 1)
```

## Implemented scope

| Plan item | Implementation |
| --- | --- |
| Phase 0 / Task 1 – WebView app | Loads `https://m.youtube.com` at startup; optional URL bar; back button walks WebView history (or leaves fullscreen first) |
| Task 2 – console bridge | `onConsoleMessage()` -> Logcat tag `PocketTransposeJS`. `[PocketTranspose]` logs always; other page logs only in debug builds; script URLs (`sourceId`) are never logged |
| Phase 1 / Task 3 – injection | `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` -> `WebViewCompat.addDocumentStartJavaScript()`; fallback `onPageFinished()` / `onProgressChanged(100)` + `evaluateJavascript()`; scripts loaded from `assets/` |
| Task 4 – media discovery | `querySelectorAll("video, audio")` (prefers the playing element), coalesced `MutationObserver`, 1 s interval scan |
| Phase 2 / Task 5 – Web Audio | `AudioContext` (`latencyHint: "playback"`), `createMediaElementSource()`, `GainNode`, success/failure logs with exception name + message; resume on play / touch / visibility / app resume |
| Phase 3 / Task 6 – native UI | Bottom sheet: swipe the handle up (or tap) to open, down to close; back closes it. Pitch (−12…+12 st, step 1), Speed (0.25–3.00x, step 0.05), Gain (0–2.00, step 0.05), each with −/+ buttons and a slider (long-press the value to reset); "Preserve pitch", Probe only, Reset, Status, YouTube / Test page / Reinject; status JSON shown in the sheet and Logcat. The app pads its layout by the system-bar insets (targetSdk 35 is edge-to-edge on Android 15), and only video fullscreen hides the bars |
| Phase 4 / Task 8 – pitch shift | Self-written two-tap delay-line pitch shifter built **only from native Web Audio nodes** (`DelayNode` + looping curve `AudioBufferSourceNode` + `GainNode`), so it runs on the audio rendering thread. Bypassed at 0 semitones. See "Why not AudioWorklet" below |
| Phase 5 / Task 7 – YouTube SPA | `history.pushState`/`replaceState` patch, `popstate`, `yt-navigate-finish`, URL-change detection in the scan loop, re-hook of replaced media elements; settings re-applied after full reloads |
| Diagnostics | "Probe only" checkbox switches to the Phase 1 probe (reloads the page) so hook vs. no-hook behaviour can be compared; AnalyserNode-based warning `media is playing but Web Audio input is silent (possible CORS/MSE silence)` |
| §15 license policy | No third-party DSP/npm/WASM/binaries. `checkThirdPartyLicenses` Gradle task fails `preBuild`/`check` on forbidden or unknown licenses — see `THIRD_PARTY_NOTICES.md` |

### `window.PocketTranspose` API (called from native via `evaluateJavascript()` only)

| Method | Effect |
| --- | --- |
| `setGain(v)` | GainNode gain, clamped 0–4 |
| `setPlaybackRate(v)` | `media.playbackRate`, clamped 0.25–4 |
| `setPreservesPitch(bool)` | `media.preservesPitch` (pitch-corrected vs. tape-style speed change) |
| `setPitchSemitone(n)` | Pitch shift, −12…+12 semitones; 0 = bypass |
| `applySettings({...})` | All of the above at once (used after page loads) |
| `rescan()` / `resume()` | Rescan media / resume a suspended AudioContext |
| `getStatus()` | JSON: `connected`, `hasMedia`, `mediaTag`, `mediaSrcKind`, `audioContextState`, `inputLevelDb`, `silenceSuspected`, `pitchEngine`, `lastError`, `pitchError`, `routeChanges`, `settings`, … |

No `addJavascriptInterface()` bridge exists; web content cannot call into the app.

## Build and run

Requirements: Android SDK (compileSdk 35), JDK 21, access to Google Maven.

```bash
./gradlew :app:assembleDebug          # also runs checkThirdPartyLicenses
./gradlew :app:installDebug           # installs com.pockettranspose.debug
adb logcat -s PocketTransposeJS
```

Remote debugging of the page (debug builds only): `chrome://inspect` on a desktop Chrome.

## Automated checks (CI)

`.github/workflows/android.yml` runs on every pull request, on pushes to `main`, and on demand:

1. `node --test tests/*.test.cjs`: regression tests for the injected scripts (Node built-ins only, with fake DOM/Web Audio objects). They cover install/re-injection, one `MediaElementSource` per element, setting clamps and carry-over to new media, the native-node pitch engine (±12 clamp, sweep rate, curve shapes), URL redaction in logs/status, SPA rescans, silence detection, and the probe.
2. `./gradlew :app:assembleDebug :app:lintDebug`: build, license gate (`checkThirdPartyLicenses`) and lint.
3. Uploads `app-debug.apk`, the license report and the lint report as the `PocketTranspose-debug` artifact.

Run the same locally with `node --test tests/*.test.cjs` and `./gradlew :app:assembleDebug :app:lintDebug`.

## Releasing

Releases are built and published by `.github/workflows/release.yml` when a tag `v<versionName>` is pushed (for example `v0.1.0`). The workflow:
1. checks that the tag matches `versionName` in `app/build.gradle.kts`
2. runs the JS tests
3. builds a release APK signed with the keystore from repository secrets; `assembleRelease` also runs the license gate and `lintVitalRelease`
4. verifies the signature
5. creates the GitHub Release with the APK, its SHA-256 and the license report

Release notes come from `docs/releases/<tag>.md`. Without signing secrets the workflow fails rather than publish an unsigned APK.

One-time signing setup (keep the keystore and passwords safe: every future update must be signed with the same key):

```bash
keytool -genkeypair -v -keystore pockettranspose-release.jks -alias pockettranspose \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 pockettranspose-release.jks > keystore.b64   # macOS: base64 -i pockettranspose-release.jks
```

Then add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `pockettranspose` |
| `RELEASE_KEY_PASSWORD` | key password (same as the keystore password unless you chose otherwise) |

To release: bump `versionCode`/`versionName`, add `docs/releases/v<version>.md`, merge to `main`, then push the tag (`git tag v0.1.0 && git push origin v0.1.0`).

Debug builds use the package `com.pockettranspose.debug` and the label "PocketTranspose (debug)", so they can be installed next to the release build.

## Manual test procedure

### Test 1 – general HTML5 media (do this first)

1. Launch the app, tap **Test page**.
2. Swipe up the **Transpose** handle at the bottom to open the controls.
3. Tap **Create tone player** (a same-origin generated arpeggio). Expect in Logcat:
   `script installed` → `media found AUDIO blob:` → `AudioContext created` → `media element source connected`.
4. Move **Gain** and **Speed** (slider or −/+); toggle **Preserve pitch** while speed ≠ 1.00x.
5. Tap Pitch −/+ to reach +1, −1, +2, −2: the arpeggio should transpose without changing tempo or crackling. Expect `pitch engine ready native-nodes`.
6. Tap **Status**: `connected: true`, `audioContextState: "running"`, `inputLevelDb` well above −90.
7. Optional: paste a CORS-enabled media URL in section 2 of the test page.

### Test 2 – YouTube mobile

1. Tap **YouTube**, open any video and start playback.
2. Record whether Logcat shows `media element source connected` or `createMediaElementSource failed <name> <message>`.
3. Check whether the sound is still audible, and whether the `silent (possible CORS/MSE silence)` warning appears.
4. Repeat steps 3–5 of Test 1.

### Test 3 – YouTube video transition

Play video A, open video B from the suggestions, then tap **Status**. Expect `route changed; rescanning` and (if YouTube replaced the element) `media element changed` + a new `media element source connected`; `routeChanges` increases.

### Test 4 – fullscreen

Play a video, enter fullscreen, rotate, then exit with the back button or the player's button. The app must not crash, playback continues and the control panel returns.

### Test 5 – background / foreground

See **Background playback** below for the expected logs.

Play, press Home, return to the app, tap **Status**. `audioContextState` should be `running` (the app calls `PocketTranspose.resume()` in `onResume`).

### Hook vs. probe comparison

If YouTube becomes silent with the hook, enable **Probe only** (page reloads with the detection-only script) to confirm the silence is caused by the Web Audio routing and not by the page itself.

## Test result table

Results below are from the cloud build environment. The debug APK builds, but **no Android device or emulator was available**, so the Android rows must be filled in on a real device.

| # | Scenario | Success criteria | Observed result | Next action on failure |
| --- | --- | --- | --- | --- |
| 0 | Hook script in desktop headless Chromium (Playwright, local test page) | `script installed`, `media found`, `media element source connected`, gain/speed/pitch applied, signal detected, SPA re-hook | **Pass** – all logs present, `inputLevelDb` ≈ −22 dBFS, `pitchEngine: native-nodes`; `pushState` + element swap re-hooked (`hookedCount: 2`) | – |
| 0 | Native-node pitch shifter, `OfflineAudioContext` in Chromium (440 Hz sine) | Peak = 440·2^(n/12); no clicks | **Pass** – −12: 220, −2: 392, −1: 415.5, +1: 466, +2: 494, +7: 659.5, +12: 880 Hz; RMS unchanged; largest sample-to-sample step equals that of a pure sine (no discontinuities) | – |
| 0 | Pitch engine on live m.youtube.com (Chromium, mobile emulation) | Engine starts under YouTube's CSP | **Pass** – `pitchEngine: native-nodes`. (AudioWorklet via blob:, data: and same-origin URLs all fail there: `AbortError`) | – |
| 0 | `./gradlew :app:assembleDebug` (AGP 8.13.2, compileSdk 35, JDK 21) | APK builds; license gate passes | **Pass** – `app-debug.apk` built; all 12 runtime modules Apache-2.0; no Kotlin compiler warnings | – |
| 0 | `./gradlew :app:lintDebug` | No lint errors | **Pass** – 0 errors; remaining warnings are version-bump suggestions (compileSdk 36, webkit 1.17.1, Gradle 8.14.6), the `-v26` mipmap folder (required for adaptive icons), and hard-coded UI strings | – |
| 1 | General HTML5 media page (Test page) | `media found`, `media element source connected`, gain, speed, pitch audible | Not yet tested on device | Check `lastError` / `pitchError` in Status |
| 2 | YouTube mobile page | Video found and Web Audio connection success, or a clear exception log | Not yet tested on device | Record CORS/silence/element-replacement behaviour; compare with Probe only |
| 3 | YouTube video transition | New-route logs and media re-hook after selecting another video | Not yet tested on device | Strengthen SPA detection / rescan cadence |
| 4 | Fullscreen | No crash; playback and controls recover | Not yet tested on device | Adjust custom-view handling |
| 5 | Background/foreground | Status available; AudioContext resumes | Not yet tested on device | Add more resume triggers |
| 6 | YouTube background playback (device, author) | Plays in the background only for Premium | **Pass**: Premium account keeps playing with the app in the background or the screen off; a non-Premium session is paused by YouTube itself (`by=page-script`). Navigating back to search inside the page stops playback, as on YouTube mobile web | – |

## Success/failure decision table

| Item | Success criterion | Next action on failure |
| --- | --- | --- |
| JS injection | `script installed` log | Fallback injection (`onPageFinished`) is automatic; use **Reinject** |
| Video discovery | `media found VIDEO` log | Strengthen MutationObserver / interval scan |
| Web Audio connection | `media element source connected` | Check CORS / silence (`silenceSuspected`, `inputLevelDb`) |
| Sound output | Video sound audible | Re-examine graph; compare with **Probe only** |
| Gain | Audible volume change | Check JS API call path (`gain set` log) |
| Speed | Playback speed changes | Check `mediaPlaybackRate` in Status (YouTube may override it) |
| Pitch | Audible pitch change, no crackle | Check `pitchEngine` / `pitchError` in Status; if it crackles, check whether it also crackles at Pitch 0 (bypass) |
| YouTube transition | Re-scan on new video | Strengthen SPA event detection |

## Per-video settings

Transpose, speed and gain are remembered per YouTube video in a local SQLite database (`settings/VideoSettingsStore.kt`, platform `SQLiteOpenHelper`, no extra dependency):

- When the page URL switches to a video (`watch?v=`, `youtu.be/`, `/shorts/`, `/live/`, `/embed/`; parsed by `settings/YouTubeVideoId.kt`), its saved values are loaded and applied, with a short "Loaded saved settings" toast. Videos without saved values start at **transpose 0 · ×1.00 · gain 1.00**.
- Changes made in the control sheet while a video is open are saved under that video after 0.5 s (and immediately when leaving the video or the app). Values equal to the defaults delete the row, so Reset (or long-pressing each value) forgets a video.
- "Preserve pitch" stays a global setting. Pages that are not a video keep the current values.
- All disk I/O runs on one background thread. The database stays on the device and is excluded from cloud backup and device transfer.

## Background playback

Android WebView pauses hidden `<video>` on its own (mobile Chrome does not), so while media plays in the background the app keeps the WebView's window "visible" **to the engine only** (`PocketWebView.holdWindowVisible`) and at the same time tells the **page** the truth through `PocketTranspose.setAppHidden(true)`: `document.hidden` / `visibilityState` report `hidden`, `hasFocus()` is false, and `visibilitychange` + `blur` fire, also in child frames. The site therefore makes the same decision it makes in a mobile browser. This is only enabled in hook mode with document-start injection (so every frame is told); otherwise the WebView is hidden normally.

When page media starts playing, the hook logs `[PocketTranspose] playback state playing`; the app reads that line through the existing console bridge (no `addJavascriptInterface`) and starts a `mediaPlayback` foreground service with a notification (Pause / Stop). While media plays, leaving the app does not pause the WebView, and the service keeps the process and WebView renderer from being frozen or killed. After a pause the service lingers 5 minutes (so a playlist can advance without a background restart, which Android 12+ forbids) and then stops.

The page is never told it is visible while the app is in the background. Whether a site keeps playing when hidden is the site's decision: YouTube allows background playback on mobile web only for **YouTube Premium** members ([YouTube Help](https://support.google.com/youtube/answer/7356379)); other HTML5 media normally keeps playing.

If media is paused while the app is in the background, Status shows the cause as `lastHiddenPause`, and `adb logcat -s PocketTransposeJS` after pressing Home during playback shows:

| Log after going to background | Meaning |
| --- | --- |
| `page hidden mediaPlaying=true`, then nothing | Playing in the background (expected) |
| `media paused while page hidden by=page-script` | The page's own script paused it. For YouTube with Premium: YouTube did not treat this WebView as a mobile browser; try **Identify as Chrome** (below) |
| `media paused while page hidden by=browser` | WebView paused the hidden media itself (no script `pause()` call) |
| `media paused while page hidden by=app` | The notification's Pause/Stop was used |
| `playback service not started: ForegroundServiceStartNotAllowedException` | Playback started while the app was already in the background; open the app once while playing |

**Identify as Chrome** (control sheet, off by default, persisted, reloads the page) removes the `; wv` and `Version/x` tokens from the User-Agent and renames the `Android WebView` client-hint brand to `Google Chrome`, so sites see a mobile Chrome browser. It exists for YouTube Premium background playback, which YouTube may only offer to mobile browsers; it does not change what an account is entitled to (non-Premium accounts still get YouTube's normal pause-when-hidden behaviour). Note that Google discourages signing in to Google accounts inside embedded WebViews.

## Known limitations / risks

- **YouTube CORS / MSE silence**: `createMediaElementSource()` may succeed while the output is silent. The analyser-based warning makes this visible, but it cannot be fixed from the page side (bypassing it is out of scope).
- The pitch shifter is a simple time-domain design (≈50 ms window): fine for listening/practice, with some "chorus"-like artefacts on large shifts and ~25 ms of added latency. A higher-quality algorithm would need a permissively-licensed library or more DSP work (see license policy).

### Why not AudioWorklet

The first version ran the shifter in an `AudioWorkletNode` (module from a Blob URL) with a `ScriptProcessorNode` fallback. On YouTube this crackled: YouTube's CSP contains `script-src 'nonce-…' 'strict-dynamic'`, which blocks *every* worklet module URL (blob:, data:, and even same-origin), so the shifter fell back to `ScriptProcessorNode`, whose callback runs on YouTube's busy main thread and underruns. The native-node design needs no script on the audio path, so CSP does not apply and there is no main-thread dependency.
- `mediaPlaybackRequiresUserGesture = false` is a PoC convenience; YouTube may still reset `playbackRate` on its own.
- The WebView, its cookies and the injected script stay inside this app only; nothing is injected into other apps or browsers.

## Security and privacy

- Injected JS runs only in this app's WebView; no `addJavascriptInterface()`.
- WebView debugging only in debug builds; `allowBackup=false` plus `data_extraction_rules.xml` / `backup_rules.xml` keep cookies and site data out of cloud backup and device transfer; file/content access disabled; mixed content stays at the platform default.
- Logs never contain full URLs (only the media source scheme, e.g. `blob:`; URLs inside exception messages are replaced with `<url>`), cookies, or account data. Status JSON has no URL.

## License policy status

No pitch-shifting, time-stretching, npm, WebAssembly, or native DSP library has been added; the pitch shifter is original code. `checkThirdPartyLicenses` enforces the allow/deny list on every build. See `THIRD_PARTY_NOTICES.md` before adding any dependency.
