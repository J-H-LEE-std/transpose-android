# Third-party notices

This PoC intentionally avoids third-party DSP, pitch-shifting, npm, WebAssembly, and prebuilt binary dependencies.
The pitch shifter in `app/src/main/assets/pocket_transpose_hook.js` (`definePitchShifter`) was written from scratch for this project; no external DSP/FFT code was copied.

## Direct dependencies

| Library | Version | Purpose | Direct/transitive | License | Evidence | Needed because | Standard/API alternative |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | Android application build tooling (build-time only, not shipped) | Direct build plugin | Apache-2.0 | https://developer.android.com/build/releases/gradle-plugin | Required to build an Android app with Gradle | None practical for this project |
| Kotlin Gradle Plugin | 2.0.21 | Kotlin compilation (build-time only) | Direct build plugin | Apache-2.0 | https://github.com/JetBrains/kotlin | Required to compile Kotlin sources | Java-only implementation possible, but project requires Kotlin |
| Foojay toolchains resolver | 1.0.0 | Gradle JDK toolchain download (build-time only) | Direct settings plugin | Apache-2.0 | https://github.com/gradle/foojay-toolchains | Resolves the JDK 21 toolchain | Install JDK 21 manually |
| Gradle wrapper | 8.14.3 | Reproducible Gradle version (`gradlew`, build-time only) | Build tooling | Apache-2.0 | https://github.com/gradle/gradle | Pins the Gradle version that AGP 8.13 requires | Install Gradle manually |
| AndroidX WebKit | 1.12.1 | `WebViewCompat.addDocumentStartJavaScript`, `WebViewFeature`, `WebViewAssetLoader` | Direct runtime dependency | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/webkit | Document-start injection is a core requirement; asset loader serves the HTML5 test page from a secure origin | `onPageFinished` fallback exists, but document-start injection is required |

## Transitive (runtime) dependencies

The authoritative list is generated on every build by the `checkThirdPartyLicenses` Gradle task (see below) and written to
`app/build/reports/third-party-licenses/third-party-licenses.md`. Copy that table here after a dependency change.

Known entries, verified from Maven Central POMs:

| Library | Version | Pulled in by | License | Evidence |
| --- | --- | --- | --- | --- |
| org.jetbrains.kotlin:kotlin-stdlib | 2.0.21 | Kotlin Gradle Plugin | Apache-2.0 | https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom |
| org.jetbrains:annotations | 13.0 | kotlin-stdlib | Apache-2.0 | https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom |

AndroidX WebKit's own transitive AndroidX modules (for example `androidx.annotation`, `androidx.core`) are published on Google Maven,
which was not reachable from the environment where this PoC was written. **They have not been listed by hand on purpose**:
run `./gradlew :app:checkThirdPartyLicenses` once and paste the generated table here. AndroidX artifacts are published under Apache-2.0,
and the task fails the build if any of them is not.

## License verification automation

`gradle/license-check.gradle.kts` (applied from `app/build.gradle.kts`) is written with Gradle's own APIs only, so the checker adds no third-party
dependency of its own. It:

1. collects every external module on `debugRuntimeClasspath` / `releaseRuntimeClasspath`,
2. resolves each module's POM (walking parent POMs when `<licenses>` is inherited),
3. classifies licenses: Apache / MIT / BSD / ISC / Zlib are allowed; GPL, LGPL, AGPL, MPL, EPL, CDDL, CPL, OSL, SSPL and other
   copyleft/reciprocal licenses are forbidden; anything unrecognised is unknown,
4. writes the Markdown report and **fails `preBuild` and `check`** when a module is forbidden, dual-licensed with a forbidden option,
   or unknown.

A module can only be accepted manually by adding it to `manuallyReviewedLicenses` in that script **and** recording the evidence in this file.

## Explicitly excluded libraries

The project does not include Rubber Band Library, SoundTouch, TarsosDSP, or any GPL/LGPL/AGPL/MPL/EPL/CDDL dependency,
in any form (dependency, vendored/copied source, binary, WebAssembly, `.so`, `.aar`, `.jar`, npm package).
