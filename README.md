# PocketTranspose

PocketTranspose is an Android Kotlin WebView proof-of-concept for testing whether audio from a YouTube or HTML5 `<video>/<audio>` element can be connected to a Web Audio API graph inside an app-controlled WebView, and then adjusted (gain, speed, pitch) from native Android controls — similar in spirit to the Transpose Chrome extension.

The primary question this PoC answers: **does `AudioContext.createMediaElementSource(video)` work (and stay audible) for YouTube inside Android WebView?** If YouTube fails but a plain HTML5 media page succeeds, the first PoC still counts as a success.

## Architecture

```
MainActivity (URL bar, WebView, control panel, fullscreen container)
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
```

## Implemented scope

| Plan item | Implementation |
| --- | --- |
| Phase 0 / Task 1 – WebView app | Loads `https://m.youtube.com` at startup; optional URL bar; back button walks WebView history (or leaves fullscreen first) |
| Task 2 – console bridge | `onConsoleMessage()` -> Logcat tag `PocketTransposeJS`. `[PocketTranspose]` logs always; other page logs only in debug builds; script URLs (`sourceId`) are never logged |
| Phase 1 / Task 3 – injection | `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` -> `WebViewCompat.addDocumentStartJavaScript()`; fallback `onPageFinished()` / `onProgressChanged(100)` + `evaluateJavascript()`; scripts loaded from `assets/` |
| Task 4 – media discovery | `querySelectorAll("video, audio")` (prefers the playing element), coalesced `MutationObserver`, 1 s interval scan |
| Phase 2 / Task 5 – Web Audio | `AudioContext` (`latencyHint: "playback"`), `createMediaElementSource()`, `GainNode`, success/failure logs with exception name + message; resume on play / touch / visibility / app resume |
| Phase 3 / Task 6 – native UI | Gain (0–2.00), Speed (0.25–3.00x), Pitch (−12…+12 semitones), "Preserve pitch" (`preservesPitch`), YouTube / Test page / Reinject / Status buttons, status JSON shown on screen and in Logcat |
| Phase 4 / Task 8 – pitch shift | Self-written two-tap delay-line pitch shifter (no third-party code). Runs as an `AudioWorkletNode` (module loaded from a Blob URL); falls back to `ScriptProcessorNode`; bypassed entirely at 0 semitones |
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
./gradlew :app:installDebug
adb logcat -s PocketTransposeJS
```

Remote debugging of the page (debug builds only): `chrome://inspect` on a desktop Chrome.

## Manual test procedure

### Test 1 – general HTML5 media (do this first)

1. Launch the app, tap **Test page**.
2. Tap **Create tone player** (a same-origin generated arpeggio). Expect in Logcat:
   `script installed` → `media found AUDIO blob:` → `AudioContext created` → `media element source connected`.
3. Move **Gain** and **Speed**; toggle **Preserve pitch** while speed ≠ 1.00x.
4. Move **Pitch** to +1, −1, +2, −2: the arpeggio should transpose without changing tempo. Expect `pitch engine ready audioworklet` (or `scriptprocessor`).
5. Tap **Status**: `connected: true`, `audioContextState: "running"`, `inputLevelDb` well above −90.
6. Optional: paste a CORS-enabled media URL in section 2 of the test page.

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

Play, press Home, return to the app, tap **Status**. `audioContextState` should be `running` (the app calls `PocketTranspose.resume()` in `onResume`).

### Hook vs. probe comparison

If YouTube becomes silent with the hook, enable **Probe only** (page reloads with the detection-only script) to confirm the silence is caused by the Web Audio routing and not by the page itself.

## Test result table

Results below are from the cloud build environment. The debug APK builds, but **no Android device or emulator was available**, so the Android rows must be filled in on a real device.

| # | Scenario | Success criteria | Observed result | Next action on failure |
| --- | --- | --- | --- | --- |
| 0 | Hook script in desktop headless Chromium (Playwright, local test page) | `script installed`, `media found`, `media element source connected`, gain/speed applied, AudioWorklet pitch engine, signal detected, SPA re-hook | **Pass** – all logs present, `inputLevelDb` ≈ −22 dBFS, `pitchEngine: audioworklet`; with AudioWorklet removed it falls back to `scriptprocessor`; `pushState` + element swap re-hooked (`hookedCount: 2`) | – |
| 0 | Pitch DSP offline test (Node, 440 Hz sine) | Peak frequency = 440·2^(n/12) | **Pass** – −12: 220 Hz, −2: 392, −1: 415.5, +1: 466, +2: 494, +12: 880 Hz; level unchanged | – |
| 0 | `./gradlew :app:assembleDebug` (AGP 8.13.2, compileSdk 35, JDK 21) | APK builds; license gate passes | **Pass** – `app-debug.apk` built; all 12 runtime modules Apache-2.0; no Kotlin compiler warnings | – |
| 0 | `./gradlew :app:lintDebug` | No lint errors | **Pass** – 0 errors; remaining warnings are version-bump suggestions (compileSdk 36, webkit 1.17.1, Gradle 8.14.6), missing app icon, `allowBackup` deprecation, and hard-coded UI strings | – |
| 1 | General HTML5 media page (Test page) | `media found`, `media element source connected`, gain, speed, pitch audible | Not yet tested on device | Check `lastError` / `pitchError` in Status |
| 2 | YouTube mobile page | Video found and Web Audio connection success, or a clear exception log | Not yet tested on device | Record CORS/silence/element-replacement behaviour; compare with Probe only |
| 3 | YouTube video transition | New-route logs and media re-hook after selecting another video | Not yet tested on device | Strengthen SPA detection / rescan cadence |
| 4 | Fullscreen | No crash; playback and controls recover | Not yet tested on device | Adjust custom-view handling |
| 5 | Background/foreground | Status available; AudioContext resumes | Not yet tested on device | Add more resume triggers |

## Success/failure decision table

| Item | Success criterion | Next action on failure |
| --- | --- | --- |
| JS injection | `script installed` log | Fallback injection (`onPageFinished`) is automatic; use **Reinject** |
| Video discovery | `media found VIDEO` log | Strengthen MutationObserver / interval scan |
| Web Audio connection | `media element source connected` | Check CORS / silence (`silenceSuspected`, `inputLevelDb`) |
| Sound output | Video sound audible | Re-examine graph; compare with **Probe only** |
| Gain | Audible volume change | Check JS API call path (`gain set` log) |
| Speed | Playback speed changes | Check `mediaPlaybackRate` in Status (YouTube may override it) |
| Pitch | Audible pitch change | Check `pitchEngine` / `pitchError`; AudioWorklet may be blocked by page CSP → ScriptProcessor fallback |
| YouTube transition | Re-scan on new video | Strengthen SPA event detection |

## Known limitations / risks

- **YouTube CORS / MSE silence**: `createMediaElementSource()` may succeed while the output is silent. The analyser-based warning makes this visible, but it cannot be fixed from the page side (bypassing it is out of scope).
- The pitch shifter is a simple time-domain design (≈50 ms window): fine for listening/practice, with some "chorus"-like artefacts on large shifts and ~25 ms of added latency. A higher-quality algorithm would need a permissively-licensed library or more DSP work (see license policy).
- `mediaPlaybackRequiresUserGesture = false` is a PoC convenience; YouTube may still reset `playbackRate` on its own.
- The WebView, its cookies and the injected script stay inside this app only; nothing is injected into other apps or browsers.

## Security and privacy

- Injected JS runs only in this app's WebView; no `addJavascriptInterface()`.
- WebView debugging only in debug builds; `allowBackup=false`; file/content access disabled; mixed content stays at the platform default.
- Logs never contain full URLs (only the media source scheme, e.g. `blob:`), cookies, or account data. Status JSON has no URL.

## License policy status

No pitch-shifting, time-stretching, npm, WebAssembly, or native DSP library has been added; the pitch shifter is original code. `checkThirdPartyLicenses` enforces the allow/deny list on every build. See `THIRD_PARTY_NOTICES.md` before adding any dependency.
