# Third-party notices

This PoC intentionally avoids third-party DSP, pitch-shifting, npm, WebAssembly, and prebuilt binary dependencies.

## Direct dependencies

| Library | Version | Purpose | Direct/transitive | License | Evidence | Needed because | Standard/API alternative |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | 8.7.3 | Android application build tooling | Direct build plugin | Apache-2.0 | https://developer.android.com/build/releases/gradle-plugin | Required to build an Android app with Gradle | None practical for this project |
| Kotlin Gradle Plugin | 2.0.21 | Kotlin compilation | Direct build plugin | Apache-2.0 | https://github.com/JetBrains/kotlin | Required to compile Kotlin sources | Java-only implementation possible, but project requires Kotlin |
| AndroidX WebKit | 1.12.1 | WebView compatibility APIs, including document-start script feature detection/injection | Direct runtime dependency | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/webkit | Required for `WebViewCompat.addDocumentStartJavaScript` and `WebViewFeature` | Fallback `onPageFinished` injection exists, but document-start injection is a core requirement |

## Transitive dependencies

Transitive dependency inventory should be generated from Gradle dependency reports in the final build environment. No hand-vendored third-party source, JavaScript package, WebAssembly artifact, `.so`, `.aar`, or `.jar` has been added.

## Explicitly excluded libraries

The project does not include Rubber Band Library, SoundTouch, TarsosDSP, or any GPL/LGPL/AGPL/MPL/EPL/CDDL dependency.
