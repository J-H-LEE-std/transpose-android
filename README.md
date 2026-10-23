# PocketTranspose

**English** · [한국어](README.ko.md)

Change the **key**, **speed** and **volume** of YouTube videos on your Android phone, for practicing an instrument, singing along in a comfortable key, or just listening.

PocketTranspose opens the YouTube mobile site (or any web page with audio or video) inside the app and adjusts the sound as it plays.

## Features

- **Transpose** the key from −12 to +12 semitones without changing the tempo
- **Speed** from ×0.25 to ×3.00, keeping the original pitch or not, as you choose
- **Volume** from 0 to ×2.00
- **Remembers your settings per video:** open a video again and its key, speed and volume come back
- **Background playback:** keeps playing with the app in the background or the screen off, for YouTube Premium members (see below)
- A swipe-up control panel with −/+ buttons that's easy to use one-handed

## Requirements

- Android 8.0 or newer
- An internet connection
- For background playback of YouTube: a YouTube Premium account

## Install

1. Open the [latest release](https://github.com/J-H-LEE-std/transpose-android/releases/latest) on your phone.
2. Download `PocketTranspose-vX.Y.Z.apk`.
3. Open the downloaded file. If Android asks, allow installing apps from this source (your browser or file manager).

**Updating:** install the newer APK the same way. It updates the app in place and keeps your saved settings.

The `.sha256` file next to each APK lets you check that the download is intact. This step is optional.

## How to use

1. Open **PocketTranspose**. The YouTube mobile site loads. Sign in there if you like.
2. Play a video.
3. **Swipe up the "Transpose" bar** at the bottom of the screen (or tap it) to open the controls.
4. Use the **−/+ buttons** or the sliders:
   - **Pitch:** the key, in semitones
   - **Speed:** the playback speed
   - **Gain:** the volume
5. Swipe the panel down, tap the bar, or press Back to close it. The bar shows your current settings, for example `+2 st · 1.00x · gain 1.00`.

Your settings are **saved for that video automatically**. A video you haven't adjusted starts at key 0, ×1.00 speed and volume 1.00.

To reset a single setting, long-press its value. **Reset** puts all three back to the defaults (and forgets this video's saved settings).

You can also type another web address in the bar at the top to adjust the sound of other sites with audio or video.

### Other controls in the panel

| Control | What it does |
| --- | --- |
| Preserve pitch on speed change | On (default): changing the speed keeps the key. Off: faster also sounds higher, like a tape. |
| Identify as Chrome (Premium background play) | Makes YouTube treat the app like the Chrome browser. Only try this if background playback doesn't work for you with Premium. The page reloads. |
| YouTube | Goes back to the YouTube home page. |
| Test page | A built-in page that plays a short tone. Use it to check that the controls work. |
| Reinject | Reconnects the controls if they stop affecting the sound. |
| Status | Shows technical details, useful if you report a problem. |
| Probe only | A developer diagnostic. Leave it off. |

## Background playback

When you first open it, PocketTranspose asks for permission to show notifications. While it plays, a **"PocketTranspose is playing"** notification lets you pause or stop.

- **YouTube:** YouTube decides whether a video keeps playing in the background, exactly as in a mobile browser. With **YouTube Premium** it keeps playing; without Premium it pauses when you leave the app. PocketTranspose doesn't get around this.
- **Other sites:** most audio and video simply keep playing.
- If you have Premium and it still pauses, turn on **Identify as Chrome** in the panel.
- Going back to the search results inside YouTube stops playback. YouTube's mobile site does the same.

## Troubleshooting

**Moving the sliders doesn't change the sound**
Make sure the video is actually playing, then tap **Reinject**. On the **Test page** the tone should change when you move Pitch.

**Large key changes sound a little grainy**
This is a known limit of the transposer, and it shows most at around ±12. Smaller shifts sound clean.

**The sound is very slightly behind the picture while transposing**
Transposing adds about 20–30 ms of delay, which is barely noticeable for music.

**Playback stops when I leave the app**
For YouTube this is expected without Premium. With Premium, see the **Identify as Chrome** tip above, and check that notifications are allowed for PocketTranspose.

## Privacy

- PocketTranspose has no account, no ads, no analytics and no server of its own. It only talks to the sites you open.
- Signing in to YouTube happens on YouTube's own page, and the sign-in stays inside the app.
- Your per-video settings are stored only on your phone and are excluded from cloud backups and device transfers.

## Not affiliated

PocketTranspose is an independent hobby project. It isn't affiliated with or endorsed by YouTube, Google, or the "Transpose" browser extension. It doesn't block ads or get around YouTube's restrictions.

## For developers

Building, architecture, tests and the release process are described in the [developer guide](docs/DEVELOPMENT.md). Quick build: `./gradlew :app:assembleDebug`.

## License

PocketTranspose is released under the [BSD 2-Clause License](LICENSE). This applies to every version of PocketTranspose, including v0.1.0 and v0.1.1, which were published before the license file was added.

The app includes third-party components under their own licenses (Apache-2.0); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The software is provided "as is", without warranty of any kind.
