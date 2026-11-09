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

Tap the playlist button to show or hide the queue. Long-press it to choose another folder. Tap Shuffle to switch between shuffled and sequential playback; the queue repeats. Long-press Shuffle to switch Glyph output between bars and dots. Audio visualization requires microphone permission for Android's audio Visualizer API.

## Microphone Visualizer

Open **Microphone Visualizer** from the home screen, tap **Start microphone**, and grant microphone access. Nearby sound is split into low, mid and high frequency bands and displayed on screen. Enable **Glyph Interface** to drive the A (11 LEDs), B (5 LEDs) and C (20 LEDs) strips respectively. **Dot mode** lights one LED per active strip instead of a bar.

The connection status appears below the Glyph switch. Audio is processed locally in memory; it is never saved or uploaded. Stopping capture, leaving the screen or locking the phone stops microphone capture and turns off the lights. Returning to the screen requires tapping Start again.

## Android APIs

Playback follows the official [MediaSessionService background playback guide](https://developer.android.com/media/media3/session/background-playback) and [MediaController connection guide](https://developer.android.com/media/media3/session/connect-to-media-app). Microphone input uses [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord) with mono PCM16 at 44.1 kHz on a dedicated worker thread. Glyph registration, session lifecycle and LED indices follow the Nothing SDK documentation linked above.

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
