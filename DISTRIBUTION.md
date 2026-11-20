# Building and distributing ReadAlong

ReadAlong uses one Android source tree with separate `github` and `play` product flavors. Shared playback, reading and transcription code lives in `app/src/main`; distribution-specific code lives in the corresponding source set.

## Build commands

| Output | Command |
| --- | --- |
| GitHub debug APK | `./gradlew :app:assembleGithubDebug` |
| GitHub release APK | `./gradlew :app:assembleGithubRelease` |
| Store release bundle | `./gradlew :app:bundlePlayRelease` |

Configure the Android SDK locally through Android Studio or an untracked `local.properties`. Release outputs require signing before installation or submission.

## Signing and application identity

Updates to an existing installation need the same application ID and a compatible signing certificate. Keep signing keys outside source control. Before a first store submission, choose the final application ID and production signing setup. Separate application IDs allow both distributions to be installed together; a shared ID requires a coordinated signing and upgrade strategy.

## Source and licenses

ReadAlong's application code is licensed under GPLv3 with the Needle Runtime Linking Exception at the end of LICENSE. Publish the corresponding application source, JNI bridge, build scripts and version tag with distributed binaries. Third-party libraries and fonts retain their own licenses.

The pinned Apache-2.0 Needle native library is an upstream binary. The exception permits linking and distributing that unmodified runtime without its native source; distributed changes to ReadAlong itself still carry GPLv3 source obligations. Preserve the runtime's license and provenance.

## GitHub downloads

GitHub Releases distribute the `github` APK only. Never upload a `play` APK, store AAB or paid-build Actions artifact to the public repository. APK and AAB files are ignored in source control.

Publish a tagged source revision, upload the matching GitHub APK, and verify the remote file's digest against the local build. Keep the README and About download links pointed at that APK. Release titles should identify the app and version; descriptions should explain its features for readers. Keep local historical binaries outside source control for recovery.

## Audible import

Audible sign-in and import (`app/src/audible/.../audible`, `data/audible`, `ui/audible`) ship in both the `github` and `play` flavors and in the desktop apps. The shared `app/src/audible` source set is added to both flavors in `app/build.gradle.kts`, and the desktop build compiles the same engine from it.

The engine is GPL-3.0 code ported from LibriSync and Libation, whose authors have not granted the Needle Runtime Linking Exception. A GitHub release therefore needs the Needle runtime's source available as part of its Corresponding Source: link the runtime's published Apache-2.0 source and version in the release notes, or attach it.

## Support and store distribution

The GitHub download is free. Its optional tip jar opens https://ko-fi.com/readalong in the device's browser. The card and jar artwork live only in `app/src/github`. The `play` source set supplies an empty section and contains no Ko-fi URL or tipping UI.

The public support URL is configured with `readalongKoFiUrl` in gradle.properties; no private payment API key is needed. Creator payment accounts are connected in Ko-fi. Contributor mode can be disabled for zero Ko-fi platform fees on basic one-time tips; payment-processor fees still apply.

## Google Play edition: free trial and unlock

The Google Play edition is a free download with every feature for 90 days from first install. After that it keeps working, and each time it opens it shows a reminder to unlock it for $0.99, with "I have a code" and "Not now". Settings › About shows the days left and the unlock. It has no tip jar or donate button.

The unlock is a one-time in-app product with the ID `full_version` (`FullVersion.PRODUCT_ID`), sold through Google Play Billing (`app/src/play/.../ui/about/FullVersion.kt`). In Play Console, create it under Monetize with Play › Products › One-time products, set the price to $0.99 and activate it. The app listing itself stays free.

Unlock codes are Play promo codes for that product (Play Console › Monetize with Play › Promo codes, type "One-time product"). A code can be redeemed in the app ("I have a code" opens Google Play with it) or directly in the Play Store, and the app picks up the unlock when it next comes on screen. Google allows up to 500 one-time-product codes per quarter.

The trial starts at the first install time and is also kept in preferences, so restoring a backup does not restart it. The GitHub build has no trial and no unlock. The Play edition is not available on Google Play yet; its AAB is never published as a GitHub release.
