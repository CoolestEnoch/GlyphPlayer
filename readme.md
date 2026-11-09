# GlyphPlayer

A music player and microphone visualizer for **Nothing Phone (3a) and Phone (3a) Pro**. Both use the `DEVICE_24111` Glyph layout. Other devices are currently outside the supported scope.

Built with the [Nothing Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit) and Android Media3.

## Glyph Interface and NothingKey

The `NothingKey` field is application metadata in `app/src/main/AndroidManifest.xml` used by the Glyph SDK. The value in this repository is a placeholder, not a production API key.

**On Android 16 and later, Glyph Interface works directly without enabling Glyph Interface Debug Mode.** You do not need to enable Debug Mode to use the music or microphone visualizer on supported phones running these versions.

The manifest entry is retained for SDK compatibility.

On older Android versions, replace the placeholder `NothingKey` with a valid key issued by Nothing, or enable Glyph Interface Debug Mode for development.

## Music playback

Choose a folder containing MP3 or FLAC files; subfolders are included. Tap a track to play it. The player connects to a Media3 `MediaSessionService` through `MediaController`. The player and system media notification share the same queue, metadata, playback state and seek position. Notification and lock-screen controls support play/pause, previous/next and seeking. Music continues when the player screen closes. Glyph visualization follows the Nothing SDK foreground requirement and runs while the player screen is visible.

Tap the playlist button to show or hide the queue. Long-press it to choose another folder. Tap Shuffle to switch between shuffled and sequential playback; the queue repeats. Long-press Shuffle to switch Glyph output between bars and dots. Tap **Grant microphone access** to authorize Android's audio Visualizer API, then tap **Enable Glyph lights**. Neither opening the player nor pressing Play requests microphone permission; playback works without that permission.

## Microphone Visualizer

Open **Microphone Visualizer** from the home screen. Permissions are requested only by the separate **Grant microphone access** and **Grant notification access (background mode)** buttons. Granting access never starts recording or enables background mode by itself.

After granting microphone access, tap **Start microphone**. Nearby sound is split into low, mid and high frequency bands and displayed on screen. Enable **Glyph Interface** to drive the A (11 LEDs), B (5 LEDs) and C (20 LEDs) strips respectively. **Dot mode** lights one LED per active strip instead of a bar; **Reverse direction (R)** reverses each strip's lighting direction immediately.

Notification access is optional. Without it, microphone capture works while the screen is visible, pauses when the screen locks or the app goes into the background, and resumes on returning to the screen. To continue recording while locked, grant notification access separately and tap **Run in background**. This starts a microphone foreground service with an ongoing notification and a **Stop** action. **Disable background mode** returns to foreground-only capture. **Stop microphone** or the notification Stop action releases the recorder, workers, wake lock and Glyph session. A system kill or reboot does not restart recording automatically.

The connection status appears below the Glyph switch. Audio is processed locally in memory; it is never saved or uploaded. The music player, microphone and slider test use one coordinated Glyph session so other screens cannot close the microphone's active background session.

## Audio Playback Capture

**Audio Playback Capture** is a separate home-screen entry and service. It captures eligible media/game playback from other apps as PCM without opening the physical microphone. The existing **Microphone Visualizer** remains a separate feature with its own controls, state and notification.

1. Tap **Grant audio capture access** to grant Android's `RECORD_AUDIO` permission. The permission is required even though this feature does not use the microphone.
2. Tap **Authorize system audio** and approve Android's capture dialog. This only prepares a one-use authorization; it does not start capturing.
3. Tap **Start capture**. No audio or screen-image files are saved, and no virtual display is created.
4. Optionally grant notification access with its own button, then tap **Run in background** before leaving the page. Without that grant, foreground capture remains usable but background mode is disabled.

MediaProjection requires a `mediaProjection` foreground service even for foreground-only use. The service always supplies its notification; Android controls whether it appears in the drawer when notification permission is denied. When background mode is off, leaving the page stops the session. Starting again requires a new explicit **Authorize system audio** action. Notification Stop and Android's system capture Stop both release the session and turn off its lights. Capture is never restarted automatically after authorization ends or after a process kill.

The source app must allow Android playback capture, and the source must use an eligible audio usage in the same user profile. Apps that prohibit capture produce silence; the screen then explains that no capturable audio is arriving. Firmware-specific screen-lock behavior still needs verification on the device. Capture sessions are independent, but the physical Glyph lights have one owner: if another visualizer owns them, the connection status reports this rather than closing that visualizer's session.

## Glyph Slider Test

A/B/C control individual strips; T controls a combined flow starting at the selected strip. **Reverse direction (R)** applies to all four sliders and to dot mode. Changing direction, dot mode or the start strip redraws the active frame immediately. Turning lights off keeps them off until a slider is moved again.

## FFT and light output

Each audio source uses one fixed FFT/output worker and a capacity-one pending mailbox. While frame 1 is calculating, requests 2 through 9 replace one another; the next calculation is frame 9. FFT processing and the direct Glyph `setFrameColors()` call run synchronously in that worker. Only on-screen status updates are posted to the UI thread, with the same bounded latest-value policy.

FFT buffers, frequency-bin indices, windows, bit-reversal tables and twiddle steps are reused. A 36-bit mask represents the physical LEDs, so percentage mapping builds no per-frame channel lists. Identical masks skip redundant hardware writes. Session changes and manual animations invalidate the output cache.

The music visualizer uses CAVA-inspired auto-ranging, as found in [Kurve](https://github.com/luisbocanegra/kurve), with a three-strip dynamic mapping. Each frequency band tracks its own recent peaks and troughs in a bounded three-second window. Fixed-capacity monotonic deques update that window without rescanning/sorting or per-frame allocations. A fast attack and short, time-based release preserve musical peaks and valleys; the previous accumulated integral and shared gain are no longer used because they compressed movement into a few middle LEDs. Steady input remains steady, silence decays to zero, and low-level FFT leakage is gated relative to the audible signal.

Music-player Visualizer capture uses `SCALING_MODE_AS_PLAYED` so Android does not normalize away amplitude differences before analysis. System playback capture feeds linear PCM band magnitudes directly to the same envelope. Microphone capture keeps its independent input behavior. Switching tracks resets the music history. The original CAVA reference and MIT attribution remain in [cavacore.c at 5eb61ec](https://github.com/karlstav/cava/blob/5eb61ec68fbd26e729069e9f14d1e8bb31af1bc4/cavacore.c) and `app/src/main/assets/licenses/CAVA.txt` (packaged in the APK).

## Android APIs

Playback follows the official [MediaSessionService background playback guide](https://developer.android.com/media/media3/session/background-playback) and [MediaController connection guide](https://developer.android.com/media/media3/session/connect-to-media-app). System playback capture follows the official [Audio Playback Capture guide](https://developer.android.com/media/platform/av-capture). Physical microphone input uses [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) with mono PCM16 at 44.1 kHz on a dedicated worker thread. Glyph registration, session lifecycle and LED indices follow the Nothing SDK documentation linked above.

## Build and releases

Use JDK 17 and Android SDK 37 (Android 17). The project uses AGP 9.1.1, Gradle 9.3.1 and built-in Kotlin support:

```sh
./gradlew :app:assembleDebug
```

GitHub Actions runs **only when a new tag is pushed**, for example:

```sh
git tag v1.1.0
git push origin v1.1.0
```

The workflow builds the tagged source and uploads `app-release.apk` directly to the corresponding **GitHub Release**. It does not use Actions artifacts or build caches. Ordinary branch pushes and pull requests do not trigger builds or tests. The tag becomes the APK version name and the workflow run number becomes its version code.

For releases signed with your own key, configure all four repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Without these secrets, the release build uses the runner's debug signing key so the APK can still be installed for testing. This key may change between runs: uninstall the previous test build before installing an APK signed with a different key. For stable in-place updates, configure your own signing key and keep it safe. Updating an existing APK installation requires its original signing key.

[Demo (in Chinese)](https://bilibili.com/video/BV11CbJzdEfJ)
