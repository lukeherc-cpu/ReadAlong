# ReadAlong for iPhone

The iPhone app is the desktop app's screens and engine compiled for iOS with Kotlin/Native and Compose
Multiplatform, plus the phone's reading, EPUB, alignment and Audible code, the same way `desktop-app/` reuses the
phone's code. Nothing here is a second copy: the Gradle build copies those sources in unchanged and adds small
iOS versions of the Java libraries they call.

## Installing

Each release has an unsigned `ReadAlong-<version>.ipa`. There is no App Store listing, so it is sideloaded and
signed on install with your own Apple ID:

1. Install [AltStore](https://altstore.io) or [Sideloadly](https://sideloadly.io) on a Mac or Windows PC.
2. Download the `.ipa` from the release and open it with AltStore (on the iPhone) or drag it into Sideloadly.
3. With a free Apple ID the app must be refreshed every 7 days (AltStore does this by itself over Wi-Fi).

Needs iOS 16 or later. Works on the iPhone X and every newer iPhone.

## What's the same, and what differs

Everything the desktop app does: the library, player (with lock-screen and Control Center controls), reader,
ReadAlong queue with whisper.cpp transcription on the phone, EPUB matching, covers, Audible sign-in, library and
download, and Wi-Fi sync with ReadAlong Desktop.

- **Library**: the app's Audiobooks folder, which the Files app shows under On My iPhone › ReadAlong. Books added
  with + are copied there. Other folders can be added too; iOS remembers access to them.
- **Transcription** runs in the app, so keep ReadAlong open while it works (the screen stays on). The default model
  is the smallest English one (tiny.en). iPhones with an A13 chip or newer use the GPU (Metal); older ones, such as
  the iPhone X, use the processor.
- **Linking to a computer**: on the computer, Settings › Link a phone shows a code; on the iPhone, Settings › Link
  a computer scans it (or pastes it). Allow local network access when iOS asks.
- **Not supported**: OGG and Opus audio (iOS can't play them).

## Building

The `ReadAlong for iPhone` workflow (`.github/workflows/ios.yml`) builds everything on a macOS runner:
the phone's unit tests on the simulator, whisper.cpp for iOS, the Kotlin framework, and the `.ipa`.

Locally on a Mac:

```sh
./gradlew -p ios-app linkReleaseFrameworkIosArm64
# whisper.cpp: see the "Build whisper.cpp for iPhone" step in ios.yml
cd ios-app/iosApp && READALONG_VERSION=0.0.0 xcodegen generate && open ReadAlong.xcodeproj
```

- `core/`: the phone's shared code plus iOS versions of `java.*`, `javax.crypto`, `org.json` and `org.jsoup`.
- `engine/`: the desktop's library, player, transcription and link engine, with iOS versions of the parts that
  used FFmpeg, JavaFX, sockets and child processes.
- `src/`: the desktop's Compose screens, plus the few iPhone-only ones (`src/iosMain`).
- `iosApp/`: the SwiftUI shell, the whisper.cpp bridge and the XcodeGen project.
