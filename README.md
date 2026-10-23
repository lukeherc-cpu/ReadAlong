# ReadAlong

ReadAlong is a native Android audiobook player with on-device transcription and synchronized EPUB reading.

**[Download ReadAlong APK](https://github.com/lukeherc-cpu/ReadAlong/releases/download/v0.34.1-preview/ReadAlong-0.34.1-preview.apk)**

Current release: **0.34.1-preview** (version code 27). Android 8.0 or newer; ARM64 device required.

## Features

- Local audiobook files and library folders, chapter navigation, bookmarks, playback speed and sleep timer.
- Offline Cactus Whistle transcription with saved checkpoints, a serial queue and charging-only controls.
- Sentence highlighting and tap-to-seek synchronized reading, optional EPUB alignment and a separate read-only mode.
- Light/dark palettes, matching launcher icons, reading and app fonts, and custom font importing.
- Background playback and foreground-service transcription with notification controls.


## Build

Open this folder in Android Studio, or use the included Gradle wrapper. Install Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1. Use JDK 21 or the compatible JDK bundled with current Android Studio. Configure your SDK in an untracked `local.properties` or through Android Studio.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The release task produces an unsigned APK; sign it with your own release key. Keep signing keys outside source control. To update an existing installation, its signing certificate must match.

The pinned Android ARM64 Needle runtime is included under `third_party/needle/prebuilt` because the native build links it. It is a required third-party build dependency, not a previous app release. Its Apache-2.0 license and upstream README are included. Runtime speech-model weights download in the app and are not stored in this repository. Dependency and font notices are under `app/src/main/assets`.

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

Some instrumentation tests need an ARM64 device or an emulator with ARM translation, and opt-in tests require a locally installed speech model or private audiobook fixtures. Fixtures, personal books, build caches, recordings and historical APKs are intentionally excluded.

## Background transcription

Enable **Settings → Background transcription and battery → Allow background processing** for screen-off use. Queue recovery and notification Resume use the dedicated foreground service. Android battery, thermal and foreground time limits still apply.

## Downloads

Get the newest APK from this repository's GitHub Releases page. Source control contains the Android project and required build dependencies only; app releases are separate assets.

## License

ReadAlong's original application code is licensed under [GNU GPLv3](LICENSE) (`GPL-3.0-only`). You may use, modify and redistribute it, including commercially, under those terms. Distributed modified versions must provide corresponding source. Third-party dependencies and fonts retain their own licenses; see [NOTICE.md](NOTICE.md) and the bundled dependency notices.
