# PocketTranspose

PocketTranspose is an Android Kotlin WebView proof-of-concept for testing whether audio from a YouTube or HTML5 `<video>/<audio>` element can be connected to a Web Audio API graph inside an app-controlled WebView.

The first validation target is not production-quality pitch shifting. The priority is to prove JavaScript injection, media-element discovery, `AudioContext.createMediaElementSource()`, `GainNode` routing, and native slider control via `evaluateJavascript()`.

## Implemented PoC scope

- Loads `https://m.youtube.com` at startup.
- Enables JavaScript, DOM storage, and WebView media playback without an additional user-gesture requirement.
- Enables WebView debugging only for debug builds.
- Bridges JavaScript console output to Logcat with the `PocketTransposeJS` tag.
- Registers AndroidX WebKit document-start JavaScript injection when supported.
- Falls back to `onPageFinished()` + `evaluateJavascript()` reinjection.
- Injects `assets/pocket_transpose_probe.js`.
- Finds `video` or `audio` elements with `querySelector`, `MutationObserver`, and interval scanning.
- Attempts `AudioContext.createMediaElementSource(media)` and logs success or the exception name/message.
- Connects `MediaElementAudioSourceNode -> GainNode -> destination`.
- Exposes `window.PocketTranspose` with `setGain`, `setPlaybackRate`, `setPitchSemitone`, and `getStatus`.
- Adds Android controls for gain, playback speed, pitch placeholder, reinjection, and status logging.
- Watches basic YouTube SPA navigation with History API patching, `popstate`, and `yt-navigate-finish`.

## Build and run

```bash
gradle :app:assembleDebug
```

Install the generated debug APK on an Android device or emulator with a current Android System WebView/Chrome implementation, then open Logcat filtered by `PocketTransposeJS`.

## Manual test procedure

1. Launch the app.
2. Confirm that YouTube mobile loads.
3. Open Logcat and filter by `PocketTransposeJS`.
4. Look for `[PocketTranspose] script installed`.
5. Open a YouTube video or an HTML5 media page.
6. Look for `[PocketTranspose] media found VIDEO` or `[PocketTranspose] media found AUDIO`.
7. Look for either `[PocketTranspose] media element source connected` or `[PocketTranspose] createMediaElementSource failed ...`.
8. Move the Gain slider and listen for volume changes.
9. Move the Speed slider and confirm playback-rate changes.
10. Tap Status and inspect the JSON status in Logcat.

## Success/failure recording table

| Scenario | Success criteria | Observed result | Next action on failure |
| --- | --- | --- | --- |
| General HTML5 media page | `media found`, `media element source connected`, gain changes, speed changes | Not yet tested on device | Confirm media URL/CORS behavior and injected status |
| YouTube mobile page | Video found and Web Audio connection success or clear exception log | Not yet tested on device | Record CORS/silence/element-replacement behavior |
| YouTube video transition | New route logs and media rediscovery after selecting another video | Not yet tested on device | Strengthen SPA event detection and rescan cadence |
| Fullscreen | No crash; playback and controls recover after exiting fullscreen | Not yet tested on device | Improve custom-view container handling if needed |
| Background/foreground | Status remains available; AudioContext can resume | Not yet tested on device | Add additional resume triggers if needed |

## License policy status

No pitch-shifting, time-stretching, npm, WebAssembly, or native DSP library has been added. The pitch slider is currently a placeholder that logs the requested semitone value. See `THIRD_PARTY_NOTICES.md` before adding any dependency.
