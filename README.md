# PocketTranspose

Android 8.0+ Kotlin/WebView proof of concept for routing HTML5 media through Web Audio. Opens YouTube mobile by default. This implements the initial PoC scope in the supplied requirements, not a production Transpose replacement.

## Use

- Open YouTube, enter an **HTTPS** media page in the address field, or select **Test audio**.
- Tap the page's Play button. The bundled test generates a quiet, looping 440 Hz WAV locally, without a server or third-party audio file.
- **Gain**: 0–200%, including mute at zero. **Speed**: 25–200%.
- **Preserve pitch**: keep the original pitch during speed changes when supported by WebView. Turn it off for coupled speed/pitch changes.
- **Status** shows graph state and errors in an on-device dialog. Connection success does **not** prove audible output: CORS may silence a successfully created media source.
- **Reinject** rescans and resumes the existing AudioContext without wrapping media twice. **Reload** rebuilds the page if recovery fails.
- **Replace audio element** in the test page exercises media replacement. Press Play on the replacement.
- Back exits fullscreen first, then navigates WebView history.

Independent semitone pitch shifting is **not implemented**. The final initial-PoC requirements explicitly defer DSP. There is no misleading functional pitch slider and no DSP dependency.

## Build

Install JDK 17 and Android SDK platform 36/build-tools 35.0.0. Set `ANDROID_HOME` or put `sdk.dir=/your/sdk/path` in local.properties.

```sh
./gradlew :app:assembleDebug :app:lintDebug
node --test tests/probe.test.cjs
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows: use `gradlew.bat`. The wrapper pins Gradle 8.13 and checks its distribution SHA-256. AGP is 8.13.2; compile/target SDK is 36, available as a stable platform in the SDK repository used for this build.

Build verified on 2026-09-12 using JDK 17 and Gradle 8.13. APK signature verification passed (v2). Remaining Lint warnings concern untranslated UI strings and version-specific manifest attributes. The legacy back override is narrowly annotated because API 33+ already uses the framework OnBackInvokedDispatcher.

## Architecture and constraints

`MainActivity` provides native controls; `TransposeWebViewController` configures WebView and calls `evaluateJavascript`. AndroidX WebKit performs feature-checked document-start injection; page-finished injection also handles fallback and reapplies native settings. The script is top-frame-only: cross-origin iframe media is outside this PoC's scope.

The script stores one source per media element in a WeakMap. Detached graphs are disconnected; reattached elements reuse their original source. MutationObserver, one-second scan, history events and YouTube navigation events find replacements. Settings apply to newly discovered media. User interactions and foreground visibility resume suspended contexts.

Only HTTPS navigation is accepted; local file/content access and native JavaScript interfaces are disabled. No account data, cookies, tokens, page URLs or page console messages are intentionally logged. Debug builds log the script's structured diagnostics under `PocketTransposeJS`; release builds disable this and WebView debugging. WebView itself retains normal site storage. APK backup is disabled.

YouTube/DRM/CORS policies are not bypassed. Some sources may become silent after graph attachment; reloading resets the document but cannot remove a site's restriction. Background playback is not a supported feature. Settings survive page navigation within the current activity, but not process death.

## Verification record

| Check | Result | Remaining validation |
| --- | --- | --- |
| JS regression tests | 5 passed with Node | Mocks test lifecycle/parameters, not real Web Audio |
| Settings before media exists | Passed | Device slider response |
| Detach and reattach same element | Passed; source created once | Actual WebView audio routing |
| Replacement/reinjection | Passed; settings retained | YouTube SPA/ad transitions |
| Multiple media and URL-free status | Passed | Real page compatibility |
| Failed attachment, URL redaction and retry | Passed | Site-specific error behavior |
| Runtime dependency license gate | Passed: 11 Apache-2.0 artifacts | Reruns before each build |
| Android build/lint | assembleDebug + lintDebug passed; 0 errors, 11 warnings | Device installation |
| Local HTML5 test audio | Bundled and ready | Play, gain, speed and preserve-pitch listening test |
| YouTube mobile | Not tested on device | Discovery, connection, audible output/CORS |
| Fullscreen and foreground return | Implemented, not device-tested | Back restores controls; status reports context state |

## License policy

No third-party DSP, npm, WASM or native audio library is included. Existing Kotlin/AndroidX dependencies are retained. See `THIRD_PARTY_NOTICES.md`. New libraries require the user's prior approval; GPL/LGPL and other reciprocal licenses are excluded by project policy.
