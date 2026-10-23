# ReadAlong distribution

One Android codebase produces two editions. Keep features and versions shared; do not maintain a second app fork.

| Edition | Build | Support UI |
| --- | --- | --- |
| GitHub | `./gradlew :app:assembleGithubRelease` | Free download, optional Ko-fi tip jar |
| Google Play | `./gradlew :app:bundlePlayRelease` | Paid download, no tip jar or Ko-fi URL |

Set the $0.99 base price in Play Console. This is an upfront download price, not an in-app purchase or feature unlock. Google handles checkout and store updates. Do not add an external payment link to this edition.

The GitHub edition's support card and jar vector live only under `app/src/github`. The Play source set supplies an empty section. Its generated build configuration contains no Ko-fi URL. The persistent placement preference does not create any tipping UI in Play.

## Licensing

The application's code uses GPLv3-only with the narrow Needle Runtime Linking Exception at the end of LICENSE. Distributed app modifications still require corresponding source. Unmodified Apache-2.0 Needle runtime binaries can be included under the exception; preserve the upstream license and provenance. Other dependency and font licenses remain applicable.

Publish the matching application source, build scripts and version tag alongside each released binary, for both editions. Keep third-party notices and the complete application license in the app. The pinned native library is the upstream binary, not a claim that its full C++ implementation is contained here.

## Ko-fi setup

Public support page: https://ko-fi.com/readalong

Connect PayPal, Stripe or both in Ko-fi Payment Settings. For basic one-time tips with zero Ko-fi platform fee, turn Contributor mode off in Settings → Payment; payment-processor fees remain. The app opens the public page in the browser and does not collect card information, process payments, unlock features, or require a private API key. Configure only the public link with `readalongKoFiUrl` in gradle.properties.

The app link can be tested without paying. Confirm the creator's payment connection in Ko-fi itself; a successful browser open is not proof that a creator account is ready to receive payments.

## Store identity and signing

The prototype retains its existing application ID and signing certificate for GitHub upgrades. Before the first Play submission, decide the final store application ID and production signing setup. Use a separate Play application ID if both editions should coexist; otherwise the signing keys and upgrade strategy must be coordinated. Store production keys outside the repository and use Play App Signing. Set the Play listing to paid before publishing it; a published free app cannot become a paid app under the same listing. See https://support.google.com/googleplay/android-developer/answer/6334373.

## Approval and publication

The 0.35.0 preview is a local review build. Do not upload it, change repository visibility, or remove the current GitHub APK until the owner approves it. After approval:

1. Publish the tested matching source and version tag.
2. Upload only the GitHub-edition APK as the release's sole manually uploaded asset; GitHub supplies source archives automatically. Never upload a Play APK, Play AAB, or paid-build artifact to GitHub, including Actions artifacts.
3. Verify the remote APK digest before removing the superseded release and tag.
4. Update the README and About website to the new direct APK URL.
5. Make the repository public, as authorized conditional on APK approval.

Keep local historical APKs for recovery; removing the superseded GitHub release does not require deleting them from the workspace.
