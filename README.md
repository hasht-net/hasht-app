# Hasht Mobile

The iOS and Android app for [Hasht Chat](https://github.com/hasht-net/hasht-chat),
built with Capacitor. It loads the chat frontend from the instance you choose. The
frontend is not bundled here.

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
later launches. Each saved entry has its own sign-in, so you can add the same
backend more than once for different accounts. On upgrade, the first existing
entry for each backend keeps its previous sign-in. Other browser storage is
shared by origin.

The remote frontend calls native plugins through `window.Capacitor`. The [desktop
client](https://github.com/hasht-net/hasht-desktop) provides a separate Electron
bridge for the same server frontend.
[src/integration/hashtNative.ts](src/integration/hashtNative.ts) defines the bridge
contract for the chat frontend to vendor. It is not imported by the picker.

### Navigation

The frontend provides server switching through `ServerConfig.reset()`, which
returns to the picker and keeps the instance in the saved list.
`ServerConfig.signOut()` clears that instance's notifications, badge, and push
registration without navigating.

If the remote page returns an HTTP error, cannot connect, or never finishes
loading, native recovery controls offer **Retry** and **Servers** without
requiring any JavaScript from that server. Returning to the picker preserves
saved entries and their sessions. On Android, Back during a pending or failed
load also returns to the picker. A same-origin HTTP 200 page must mount the
chat UI before loading is considered complete; external SSO pages are allowed
to load normally.

On iOS, a left-edge swipe can return to the picker through WebView history.
Android back first sends an Escape key event to the page. If the page does not
handle it, back walks WebView history, then backgrounds the app.

Links to other origins open in a Safari sheet or Android Custom Tab by default.
The frontend can select the system browser through `ServerConfig.setLinkMode()`.
Instance redirects, including SSO, stay in the WebView.

### Links into the app

Invite and message links on `chat.hasht.net` open the app (iOS universal links,
Android App Links; the backend serves the association files). Any instance's
page can also hand its URL over with `hasht://open?url=<https URL>` or the iOS
Smart App Banner. Links are untrusted: only `https`, reduced to the origin plus
`/invite/<code>` or `/channels/<id>/<id>`, anything else opens the instance
root.

`DeepLinks` (Swift and Java twins) picks the entry: none on that origin adds
`chat.hasht.net` silently and asks first for any other host; one opens there;
several use the default (`ServerConfig.setDefault`) or ask, with "Always use…"
to set it. Invite and message links tapped inside the WebView that belong to a
saved server or `chat.hasht.net` go through the same router. The page reports
the signed-in account with `ServerConfig.setAccount` so the chooser can show
usernames.

The shell exposes safe-area insets as
`--hasht-safe-area-{top,right,bottom,left}`. The frontend should use these with
`env(safe-area-inset-*)` as a fallback.

### Signing in

Passwords work in the WebView. Passkeys use `NativeAuth` to open a browser
sign-in session, since WebView passkey support requires fixed domain
associations that cannot cover arbitrary self-hosted instances.

The backend's native auth flow uses PKCE, and approving in the browser needs a
passkey or password step-up. The verifier stays in native code. See the native
sign-in guide in
[hasht-chat](https://github.com/hasht-net/hasht-chat/blob/main/docs/NATIVE_SIGN_IN.md)
for the protocol.

Sign-in starts through `NativeAuth.signIn({ invite?, register? })` (both only
shape the browser's form) or a
`hasht://signin?server=<origin>` link. A link requires confirmation and only
adds or switches the instance after sign-in succeeds. The page collects the
result through `NativeAuth.takePendingAuth()`. Returning to the app while the
browser is open cancels sign-in.

### Push

`NativePush` registers with
[hasht-push-relay](https://github.com/hasht-net/hasht-push-relay) and returns a Web
Push subscription for the instance to store through `/api/push/subscribe`. The relay
forwards encrypted payloads through APNs or FCM. Decryption happens on the device in
the iOS Notification Service Extension or Android messaging service.

Each saved account has its own registration and keypair. iOS keys are shared with
the extension through the App Group Keychain (`group.net.hasht.app`); Android
keys are wrapped with Android Keystore. Tapping a notification switches to its
instance. The active instance does not show foreground banners.

Each message has its own notification identity. iOS groups by account and
conversation; Android leaves grouping to the system. Live messages alert normally.
Catch-up messages are passive on iOS and use a low-importance Android channel. The
backend sends paced batches, prioritizing DMs and mentions without discarding the
rest.

Android persists notification state in SQLite. Both platforms keep per-account
conversation read boundaries and deleted-message records. The frontend updates read
markers on reconnect and local reads; push controls can clear a whole conversation
through one boundary. Markers use server message timestamps.

iOS may skip background pushes, especially after force-quit, so remote clearing is
best effort. The notification extension quiets late alerts for messages already read
and repairs delivered notifications, but cannot discard its own incoming alert
without Apple's notification-filtering entitlement, which the app does not have.
Foreground handling suppresses those alerts, and reconnect repairs delivered
notifications. Use the unread feed to reconcile missed or stale notifications.

Quiet catch-up requires compatible relay, app, and backend releases. It uses `low`
urgency for visible catch-up and `very-low` for background read/remove controls,
without capability negotiation.

### Calls in the background

On Android, the frontend starts and ends `CallSession` with each call. Its
microphone foreground service keeps the mic available while the app is in the
background and displays an ongoing notification. It must only run during a
call. The Play Console foreground-service declaration needs a video of this
behavior.

The WebView picks the call's audio device when the mic opens, preferring a
wired, USB or Bluetooth headset over the speaker, but it can only follow
Bluetooth changes with the runtime `BLUETOOTH_CONNECT` permission. Instead of
asking for it, `CallSession` watches device changes during a call on Android 12
and later and moves the call to a headset that connects, or back to the best
remaining device when one goes away. Android 11 and below get the install-time
`BLUETOOTH` permission, which is all the WebView needs there.

iOS declares the audio background mode. Background microphone behavior still
needs device verification.

### Home-screen quick actions

Long-press the app icon for three recent conversations and, while signed in, "Snooze
notifications". Channel labels include the server name.

The frontend supplies shortcuts through `AppShortcuts.set` and collects the selected
conversation through `AppShortcuts.take()`. Shortcuts belong to the saved account
that reported them; signing out or removing it clears them. Selecting another
account's shortcut switches to it first. Android's `ShortcutActivity` forwards into
the running task to avoid rebuilding the WebView and dropping a call.

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

### Removing a saved account and managing passkeys

Removing a picker entry records its removal (in Keychain metadata on iOS), removes
its stored login, and unregisters its push subscription. Cleanup targets only that
entry's storage key. A temporary, origin-restricted WebView without a native bridge
reads the removed token for server logout. If the instance is unreachable, the
removal record prevents local restoration, and the packaged account script clears the
old key on the next visit to that origin. Remote session revocation requires
connectivity. Android backup and device-transfer restore are disabled for app data.

Security settings use `ServerConfig.openSecurityManagement({ ticket })` to open
an authenticated passkey management page in an iOS Safari sheet or Android
Custom Tab. The shell validates the 64-character hex ticket and constructs
`/?security_management=1#ticket=<ticket>` from the active instance's HTTPS origin.
It never accepts an arbitrary destination or the app's session token.

The server issues single-use tickets valid for 60 seconds. The page removes the
fragment and exchanges it for a ten-minute credential limited to passkey management,
held only in memory. Browser logins stay untouched; enrollment still requires
password/passkey verification. The server must support security handoff.
`openSecuritySettings({ userId })` remains available for older server frontends.

### Opening and saving attachments

The remote frontend uses `ServerConfig.openAttachment({ url })` and
`saveAttachment({ url, filename? })` for explicit file actions. Opening uses a
browser sheet (Android also honors the system-browser preference), including
same-instance URLs, so local/proxy storage never replaces the conversation.
Saving streams bytes natively: iOS downloads to a temporary file and presents
its save/share sheet; Android asks for a document destination and streams into
it. Cancelling either picker is a successful cancelled result. No attachment
bytes or account credentials cross the JavaScript bridge. Download redirects
are limited to ten HTTP(S) hops and cannot downgrade HTTPS to HTTP.

These file actions require a shell with the attachment methods above. Older shells
display an update message.

### Continuing web onboarding in the app

`hasht://signin?server=<https-origin>&path=<destination>` carries optional navigation
through the existing confirmed browser/PKCE sign-in. Only `/`, invite, server, or
message paths are accepted; query strings, fragments, and arbitrary URLs are
rejected. Invite destinations also prefill the browser's invitation preview.
After successful exchange, `takePendingAuth()` includes `continuation_path` in the
auth object so the companion chat frontend can restore the destination before
showing Join. Account credentials never travel in the deep link. Old frontends
ignore the additional navigation field.

A matching `hasht://auth?request_id=<id>&cancelled=1` callback cancels the current
browser session without attempting exchange, letting the browser's recovery
screen return users to the app to retry. Normal authorization still requires a
code plus the app-held PKCE verifier.


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
node --test scripts/account-session.test.mjs
npm run build
scripts/test-webpush-ios.sh
scripts/test-share-ios.sh
scripts/test-deeplinks-ios.sh
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
`group.net.hasht.app`. The app profile also needs production Push Notifications
and Associated Domains (`applinks:chat.hasht.net`).
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