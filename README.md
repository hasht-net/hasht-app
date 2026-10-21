# hasht-app

The iOS and Android app for [Hasht](https://github.com/Hasht-net), built with
Capacitor. It loads the chat frontend from the instance you choose. The frontend
is not bundled here.

## Setup

```sh
npm ci
npm run build          # builds the instance picker
npx cap sync
npm run ios            # opens Xcode
npm run android        # opens Android Studio
```

iOS uses Swift Package Manager. CocoaPods is not required.

The app ID is `net.hasht.app`. The iOS extensions use
`net.hasht.app.NotificationService` and `net.hasht.app.ShareExtension`.

For Android push notifications, put your Firebase `google-services.json` in
`android/app/` (gitignored). The app builds without it, but push registration
returns `unavailable`.

## How it works

On first launch, the instance picker offers Hasht Cloud (`chat.hasht.net`) or a
self-hosted server. Self-hosted URLs are checked through `/api/health` using a
native request. The app remembers added instances and loads the active one on
later launches. Credentials stay with each instance's origin.

The remote frontend calls native plugins through `window.Capacitor`.
[src/integration/hashtNative.ts](src/integration/hashtNative.ts) defines the
bridge contract for the chat frontend to vendor. It is not imported by the
picker.

### Navigation

The frontend provides server switching through `ServerConfig.reset()`, which
returns to the picker and keeps the instance in the saved list.
`ServerConfig.signOut()` clears that instance's notifications, badge, and push
registration without navigating.

On iOS, a left-edge swipe can return to the picker through WebView history.
Android back first sends an Escape key event to the page. If the page does not
handle it, back walks WebView history, then backgrounds the app.

Links to other origins open in a Safari sheet or Android Custom Tab by default.
The frontend can select the system browser through `ServerConfig.setLinkMode()`.
Instance redirects, including SSO, stay in the WebView.

The shell exposes safe-area insets as
`--hasht-safe-area-{top,right,bottom,left}`. The frontend should use these with
`env(safe-area-inset-*)` as a fallback.

### Signing in

Passwords work in the WebView. Passkeys use `NativeAuth` to open a browser
sign-in session, since WebView passkey support requires fixed domain
associations that cannot cover arbitrary self-hosted instances.

The backend's native auth flow uses PKCE and a match code shown in a native
alert. The verifier stays in native code. See `docs/NATIVE_SIGN_IN.md` in the
chat repo for the protocol.

Sign-in starts through `NativeAuth.signIn()` or a
`hasht://signin?server=<origin>` link. A link requires confirmation and only
adds or switches the instance after sign-in succeeds. The page collects the
result through `NativeAuth.takePendingAuth()`. Returning to the app while the
browser is open cancels sign-in.

### Push

`NativePush` registers with `Hasht-net/hasht-push-relay` and returns a Web Push
subscription for the instance to store through `/api/push/subscribe`. The relay
forwards encrypted payloads through APNs or FCM. Decryption happens on the
device in the iOS Notification Service Extension or Android messaging service.

Each instance has its own registration and keypair. iOS keys are shared with
the extension through the App Group Keychain (`group.net.hasht.app`); Android
keys are wrapped with Android Keystore. Tapping a notification switches to its
instance. The active instance does not show foreground banners.

Android replaces notifications by instance and channel, and keeps ordering
state to suppress duplicates, late updates, and withdrawn messages. If FCM
reports dropped events, the app clears stale notifications and asks the user
to open Hasht to catch up. The frontend acknowledges recovery after loading
authenticated channel and unread state. Older backends without ordering
metadata have best-effort behavior.

iOS uses APNs collapse IDs per channel. Notification removals use background
pushes, so delivery is best effort and does not work when the app is
force-quit. APNs keeps only one offline notification per app and device.

### Calls in the background

On Android, the frontend starts and ends `CallSession` with each call. Its
microphone foreground service keeps the mic available while the app is in the
background and displays an ongoing notification. It must only run during a
call. The Play Console foreground-service declaration needs a video of this
behavior.

iOS declares the audio background mode. Background microphone behavior still
needs device verification.

### Sharing from other apps on iOS

Choose Hasht in the share sheet, select an existing DM or writable text channel,
and tap Send. Text can be edited before sending. The extension accepts text,
links, photos, videos, audio, and documents, with up to 10 files totaling 1 GB.
The instance's upload limits and permissions also apply. Keep the sheet open
until sending finishes.

The extension uses the active instance and account from the last time the app
was open. Open the app once after updating to sync the session. To change
instances or start a new DM, use the main app first.

`ShareSessionSync` copies the active session token and username to the App Group
Keychain. Signing out, switching or removing the instance, or returning to the
picker clears that copy. The native extension checks the session before
sending and rejects redirects on authenticated requests. Temporary files are
removed on completion or cancellation; retries reuse completed uploads and a
stable message client ID.

## Native assets

To regenerate the committed launcher icons from
`assets/branding/hasht-mark.png`:

```sh
python3 scripts/generate-icons.py  # requires Pillow
```

iOS includes default, dark, and tinted icons. Android includes adaptive,
monochrome, and legacy icons. Release builds use the committed assets.

Startup uses a dark background (`#151517`). Android 12+ shows the Hasht mark in
the system splash; older Android and iOS use a plain dark launch screen.

## Checks

```sh
npx tsc --noEmit
npm run build
scripts/test-webpush-ios.sh
scripts/test-share-ios.sh
(cd android && ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest)
(cd ios/App && xcodebuild -scheme App -destination 'generic/platform=iOS' \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build)
```

Native projects are committed. Add new Swift files to `project.pbxproj` for
each target that uses them. App-target plugins also need explicit registration
in `MainViewController`. Keep the shared App scheme committed so CI can build.

See [AGENTS.md](AGENTS.md) for repository layout and development conventions.

## Releasing

`.github/workflows/draft.yaml` refreshes a draft release on each push to `main`.
Edit and publish the draft in GitHub Releases to start
`.github/workflows/release.yaml`.

| Release | iOS | Android |
|---|---|---|
| Prerelease, e.g. `v0.1.0-beta.3` | TestFlight | Play internal track, rolled out |
| Stable, e.g. `v0.1.0` | App Store Connect version, not submitted | Play production track, draft |

Both jobs attach an IPA or APK to the GitHub release. Final store submission is
manual. To publish a stable version, change the draft's tag and clear its
prerelease flag.

Manual dispatch can rebuild a tag and target `ios`, `android`, or `both`. For
an iOS prerelease test, the optional `ref` input selects source from a branch
while the version comes from the tag. Source overrides are rejected for stable
releases and Android builds.

Both platforms run `npm ci`, `npm run build`, and `npx cap sync <platform>`
before the native build. Generated web assets are gitignored. Release versions
are passed as build overrides and never written into tracked files.

### Build numbers

Local and CI builds share a sequence reserved through GitHub tags named
`build-number/N`. Local reservations need `gh auth login` with repository write
access. Commit and push the code before reserving a number:

```sh
HASHT_BUILD_CODE=$(python3 scripts/reserve-build-number.py)
cd android
./gradlew :app:bundleRelease \
  -PhashtVersionCode="$HASHT_BUILD_CODE" \
  -PhashtVersionName=0.1.0
```

Signing credentials must already be loaded. Use the same number as iOS
`BUILD_NUMBER` when building both platforms. Reserve once per build, keep the
tags, and never reuse a number after an upload or failed build. Gaps are fine.

CI releases run one at a time. Keep local uploads in build-number order too.
After a CI upload failure, use **Re-run all jobs** or a new dispatch to reserve
a fresh number. **Re-run failed jobs** reuses the previous number and can
collide with an upload that succeeded.

### Credentials

Store uploads are skipped when upload credentials are absent. Android still
needs a keystore to build a signed release.

| Secret | Purpose |
|---|---|
| `ANDROID_KEYSTORE` | Base64 upload keystore |
| `ANDROID_KEYSTORE_PASS` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASS` | Key password; defaults to the keystore password |
| `GOOGLE_PLAY_SERVICE_ACCOUNT` | Play publisher service-account JSON |
| `GOOGLE_SERVICES` | Base64 `google-services.json` for FCM |
| `ASC_API_ID` / `ASC_ISSUER_ID` / `ASC_API_KEY` | App Store Connect API key; `.p8` raw or base64 |
| `APPLE_ID` / `APPLE_TEAM_ID` | Apple developer account and team |
| `DISCORD_WEBHOOK` | Build failure notifications |

Keep signing credentials and certificates outside this repo.

### iOS encryption declaration

The app and both extensions set `ITSAppUsesNonExemptEncryption` to `false`.
The shell uses Apple's cryptography and networking APIs without bundling a
separate cryptographic implementation. Reassess the declaration if that changes,
including changes to linked libraries. Already-uploaded builds still need their
compliance questions completed in App Store Connect. See
[Apple's guidance](https://developer.apple.com/documentation/bundleresources/information-property-list/itsappusesnonexemptencryption).

### iOS signing

CI supports exported signing files:

| Secret | Contents |
|---|---|
| `IOS_DISTRIBUTION_P12` | Base64 distribution certificate and private key |
| `IOS_DISTRIBUTION_PASSWORD` | Certificate export password |
| `IOS_APP_PROFILE` | Base64 profile for `net.hasht.app` |
| `IOS_EXTENSION_PROFILE` | Base64 profile for `net.hasht.app.NotificationService` |
| `IOS_SHARE_PROFILE` | Base64 profile for `net.hasht.app.ShareExtension` |

All three profiles must use the distribution certificate and include App Group
`group.net.hasht.app`. The app profile also needs production Push Notifications.
The workflow removes temporary signing files after Fastlane.

Without `IOS_DISTRIBUTION_P12`, CI uses `fastlane match` in readonly mode. Set
the repository variable `MATCH_URL` to the private certificates repo's SSH URL,
and provide `MATCH_PASSWORD` and `MATCH_SSH_KEY` secrets. Bootstrap profiles
locally:

```sh
bundle install
MATCH_URL=git@github.com:YOUR_ORG/YOUR_PRIVATE_CERTIFICATES_REPO.git \
  bundle exec fastlane match appstore
```

Create App IDs and profiles for all three targets with the capabilities above.
Regenerate profiles after adding an extension. Fastlane sets manual signing
for the build and passes `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`
to all targets. iOS versions use `X.Y.Z` without a prerelease suffix.

### Android signing

Set `HASHT_KEYSTORE_PATH` to enable release signing locally. Without it,
`assembleRelease` produces an unsigned build. Pass `-PhashtVersionCode` and
`-PhashtVersionName` to set the version; defaults are `1` and `1.0`. The version
name uses the full tag without `v`, including any prerelease suffix.

The keystore contains the upload key. Play App Signing holds the app signing
key.

### Store setup

Create the app in both stores before using the release automation. Google
Play requires the first AAB upload by hand. App Store review needs a demo
account on a live instance.

## License

[MIT](LICENSE), copyright © 2026 Hasht LLC. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party licenses.
