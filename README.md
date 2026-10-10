# hasht-app

Capacitor mobile shell for [Hasht](https://github.com/Hasht-net) — a thin client that
loads a user-configured server URL. iOS and Android.

Sibling of `hasht-desktop` (Electron). Both wrap the same Vue frontend; neither
contains a copy of it.

## Launcher icons

`assets/branding/hasht-mark.png` is the supplied flat gradient logo. Run
`python3 scripts/generate-icons.py` (requires Pillow) to regenerate the committed
native assets; release builds use these files directly, with no generation step.

iOS uses `Assets.xcassets/AppIcon.appiconset` with default, dark, and tinted
appearances. The default has a light background, the dark mark lets iOS supply
its dark background, and the tinted icon is a white silhouette on black. The
logo occupies 78% of the canvas width; iOS supplies the icon shape.

Android uses a transparent foreground on a separate neutral light/dark
background, plus a monochrome silhouette for themed icons. The mark fits inside
the 66 dp safe circle on the 108 dp adaptive layer. Launcher support and settings
determine whether night resources or wallpaper-derived themed colors appear.
Legacy square and round icons are generated for Android 7. Capacitor sync
preserves these native resources.

## Architecture

The shell **remote-loads** the frontend from whichever instance the user
configures. There is no bundled copy of the chat frontend here, so app and
server versions can never skew and frontend changes ship without an app
release.

```
launch
  └─ active server stored?
       no  → bundled wizard (index.html, src/bootstrap.ts)
             ├─ Hasht Cloud → chat.hasht.net
             └─ Self-hosted → probe /api/health
                └─ ServerConfig.add(url) → persist, activate, load
       yes → webview loads that instance directly
```

The wizard mirrors `hasht-desktop`'s picker: a hosted card and a self-hosted
card, plus the list of servers already added (switch or remove). The store
holds only which servers the user added — never credentials, which stay in each
origin's own cookie jar. WKWebView isolates those per origin, so switching
servers keeps sessions separate for free.

### Getting back out

**The frontend owns server switching**, and tells the shell when the user signs
out (`ServerConfig.signOut`, which clears delivered notifications and the badge
and ends that instance's push registration, without navigating). The shell renders no control of its
own: anything it drew would sit over the chat UI forever, duplicating what the
frontend can present properly in its settings. The frontend calls
`ServerConfig.reset()` (see `forgetInstance()` in `src/integration/`), which
clears the active server — keeping it in the list — and returns to the wizard.

Two platform gestures remain, neither of them chrome:

- **iOS** — left-edge swipe; the wizard stays in the webview's history.
- **Android** — system back first closes whatever the page has open: the shell
  sends the page an Escape keydown, and the page cancels it if it acted on it.
  Otherwise back walks the webview's history and, at the bottom of it, sends
  the app to the background like any other app. It never switches server; that
  is `ServerConfig.reset()`.

The frontend offers it from the signed-out screen ("Change server"), settings,
and the server rail.

`ServerConfig` is a small custom plugin (`ios/App/App/ServerConfigPlugin.swift`,
`android/.../ServerConfigPlugin.java`) because Capacitor's `server.url` is
static config and a self-hosted client needs it chosen at runtime.

### Links to other sites

Links that leave the instance's origin do not load in the chat webview (there
would be no way back). They open in an in-app Custom Tab / Safari sheet by
default, or in the default browser if the user picks `system`
(`ServerConfig.getLinkMode` / `setLinkMode`; the frontend owns the setting UI,
see `src/integration/hashtNative.ts`). Server redirects, e.g. through an
instance's own SSO, still load in the webview.

### Where code runs

Only the picker runs from this repo's assets. Everything after it is the Vue
app served by the instance — so **native integration code lives in the chat
frontend**, not here. `src/integration/hashtNative.ts` is the reference
implementation of that contract, kept in this repo so the app and the API it
depends on version together.

The native bridge (`window.Capacitor`) is injected into whatever origin the
webview loads, so the remote frontend can call plugins directly.

### Signing in

Passwords work in the webview as on the web. **Passkeys don't**: WKWebView
only runs WebAuthn for relying parties in the app's Associated Domains
entitlement, and Android WebView only for ones linked through Digital Asset
Links — both fixed per domain, which no self-hosted instance can be. So the
`NativeAuth` plugin hands the ceremony to the OS's browser sign-in sheet and
redeems the result through the backend's `auth::native` flow (PKCE + a match
code; see `docs/NATIVE_SIGN_IN.md` in the chat repo).

| | iOS | Android |
|---|---|---|
| Sheet | `ASWebAuthenticationSession` (Safari's engine, whatever the default browser) | Auth Tab in the default browser; Custom Tab where it has none |
| Callback | `hasht://auth`, returned in-process | Auth Tab: activity result. Custom Tab: `hasht://` intent |

The verifier never leaves native code, so a code intercepted on its way back
is useless. The match code is shown in a native alert before the sheet opens,
since the sheet covers the app.

Two ways in:

- **`NativeAuth.signIn()`** from the instance's login screen — the active
  server.
- **`hasht://signin?server=<origin>`** from the web app's "Sign in to the Hasht
  app" setting on the same device. Any page can send that link naming any
  host, so the alert is also the confirm, and nothing is added or switched
  until sign-in succeeds. The auth is then parked for that server, the webview
  loads it, and the page adopts it via `NativeAuth.takePendingAuth()`.

Each sign-in carries its own server, so several configured instances never
cross. Coming back to the app while the browser is open cancels the sign-in.

### Push

The webviews have no Web Push, so the `NativePush` plugin stands in for
`PushManager.subscribe()`. It registers the device with the Hasht push relay
(`Hasht-net/hasht-push-relay`) and hands the page the same triple a browser
subscription has; the instance stores it through its usual
`/api/push/subscribe` and sends ordinary Web Push to the relay, which forwards
to APNs or FCM.

```
instance ──Web Push (aes128gcm)──▶ relay ──▶ APNs ──▶ NotificationService (iOS extension)
                                         └─▶ FCM  ──▶ HashtMessagingService (Android)
```

The payload is encrypted end to end (RFC 8291) to a key that never leaves the
device: the relay, Apple and Google forward ciphertext. The device decrypts in
the iOS Notification Service Extension or the Android messaging service, and
replaces the relay's placeholder ("Hasht — New message") with the real text.

- **One registration and keypair per instance.** The payload's `url` is only
  a path, so the key that decrypts a push is how the device knows which
  instance sent it. A tap on another instance's notification switches to it.
- **Keys** live in the Keychain under the App Group `group.net.hasht.app`
  (shared with the extension) on iOS, and in app storage wrapped by an Android
  Keystore key on Android. Both are readable while the phone is locked.
- **Per-channel replacement.** Android uses noncollapsible FCM data pushes
  and replaces notifications locally by instance/channel. Encrypted database
  timestamps order messages and edits; persisted high-water marks and removal
  tombstones suppress duplicates and late alerts across process restarts.
  Ordering state keeps 128 channels per instance, 2,048 total, for 26 hours
  (message TTL is 24 hours). Exhausting capacity triggers recovery instead of
  evicting protection and allowing withdrawn messages to reappear.
  APNs uses the backend's per-channel `Topic` as its collapse ID.
- **Edits and removes** (`kind: update | remove`) go to the app only — never
  to browsers, whose service worker must show every push it gets. Android
  suppresses withdrawn identities once their remove is delivered. FCM can
  delay normal-priority removes; their one-hour TTL can expire before delivery.
  iOS gets removes as background pushes,
  which iOS throttles and never delivers to a force-quit app, so on iOS they
  are best effort.
- **Offline overflow.** FCM retains up to 100 noncollapsible events across all
  instances on one device. When FCM reports dropped events, the app clears
  stale notifications and persists recovery flags even if permission is denied.
  A generic “Open Hasht to catch up” prompt opens the correct instance. Fresh
  ordered pushes can still display; older queued pushes cannot recreate the
  cleared notifications. The frontend acknowledges recovery only after loading
  authenticated channel/read/unread state successfully. It cannot recreate
  missed native previews while closed; authenticated background sync is a
  planned follow-up. APNs retains only one offline notification per app/device.
  Older backends without ordering metadata retain best-effort behavior;
  following overflow their unversioned pushes are conservatively suppressed
  for the protection window. Device/server clocks need to be roughly aligned.
- **Foreground**: no banner for the instance on screen, as on the web.
- `@capacitor/push-notifications` is gone: it can't share keys with an
  extension and competed for the notification-center delegate.

Android needs `google-services.json` (CI secret `GOOGLE_SERVICES`). A local
build without it still compiles; `NativePush.register` then rejects with
`unavailable`. Drop the file from the Firebase console into `android/app/`
(gitignored) to receive pushes on a debug build.

The decryptors are checked against RFC 8291's test vector:
`scripts/test-webpush-ios.sh` (Swift, the code the extension runs) and
`./gradlew :app:testDebugUnitTest` (Java).

### Calls in the background (Android)

Android silences a backgrounded app's mic about six seconds after it leaves
the screen, and the WebView's track still reports `live`, so the page cannot
notice. The frontend mirrors each call's lifetime into `CallSession`
(`startCallSession()` / `endCallSession()` in `src/integration/`), which runs
`OngoingCallService`: a `microphone`-typed foreground service with an ongoing
notification. That service is the use Play's `FOREGROUND_SERVICE_MICROPHONE`
declaration describes, so it must only ever run during a call — the Play
Console declaration needs a video of exactly that.

## Setup

```sh
npm install
npm run build          # builds the bootstrap picker only
npx cap sync
npm run ios            # opens Xcode
npm run android        # opens Android Studio
```

Capacitor 8 uses Swift Package Manager on iOS — **no CocoaPods required**.

- Bundle ID: `net.hasht.app` (extension: `net.hasht.app.NotificationService`)
- Deep link scheme: `hasht://` (consumed by `auth::native`)
- iOS declares `UIBackgroundModes: audio` — see "Open questions" below.

## Releasing

Two workflows, mirroring `Hasht-net/hasht-chat`:

- **`.github/workflows/draft.yaml`** — every push to `main` recreates a single
  draft release with freshly generated notes and the next version
  (`v0.1.0-beta.1` → `v0.1.0-beta.2`). Nothing ships. Publishing is manual: edit
  the draft in the Releases UI and hit publish. To cut a stable version, change
  the draft's tag to `v0.1.0` and clear the prerelease flag before publishing.

- **`.github/workflows/release.yaml`** — runs on publish, one job per platform.
  Both binaries share one build number reserved from the same sequence used
  by local builds. Stores reject reused or decreasing build numbers.

| release | iOS | Android |
|---|---|---|
| prerelease `v0.1.0-beta.3` | TestFlight | Play `internal`, rolled out |
| stable `v0.1.0` | App Store Connect version, **not** submitted | Play `production`, **draft** |

Neither store submission is automatic — the last step is always a human. Both
jobs also attach their artifact (`.ipa`, `.apk`) to the GitHub release, so a
self-hoster can sideload the exact build that went to the stores.

`workflow_dispatch` rebuilds an existing tag (`tag` input) and can target one
platform (`platforms: ios | android | both`) to retry a single leg.

### Build numbers for local and CI builds

`scripts/reserve-build-number.py` reserves the next number by creating a
lightweight GitHub tag `build-number/N` at the commit being built. CI uses its
existing repository token; local builds need `gh auth login` with repository
write access. These tags are reservations, not published releases. CI previously
reached build 4 and the local Play beta used 5, so the first reservation is **6**.

Before a local signed build, commit and push its code, then run from that
checkout:

```sh
HASHT_BUILD_CODE=$(python3 scripts/reserve-build-number.py)
cd android
./gradlew :app:bundleRelease \
  -PhashtVersionCode="$HASHT_BUILD_CODE" \
  -PhashtVersionName=0.1.0
```

Use the same number for iOS `BUILD_NUMBER` if building both platforms. Signing
credentials must already be loaded. Reserve once per build, keep reservation
tags, and never reuse a number after an upload or failed build. Unused numbers
are harmless. The shared integer is capped at Google Play's version-code limit
of 2,100,000,000.

CI releases run one at a time across all tags. Keep local store uploads ordered
with CI uploads too: upload a reserved build before starting a newer store
build. To get a fresh number after a CI upload failure, use **Re-run all jobs**
or a new workflow dispatch. **Re-run failed jobs** reuses the successful meta
job's number and may collide with an upload that already succeeded.

### The shared build step

Both jobs run the same three commands before touching a native toolchain:

```sh
npm ci
npm run build          # the bootstrap picker
npx cap sync ios|android
```

That is the whole cross-platform surface. `ios/App/App/public/` and the Android
assets dir are generated, gitignored, and never committed — only the native
*projects* are (see AGENTS.md).

### Secrets

Store upload steps no-op with a `::notice::` when their credentials are absent,
so the workflow is useful (builds + release assets) before the store accounts
exist. Android still requires a keystore to produce a release build at all.

| Secret | Used by | What it is |
|---|---|---|
| `ANDROID_KEYSTORE` | Android | base64 of the upload keystore (**required**) |
| `ANDROID_KEYSTORE_PASS` | Android | keystore password |
| `ANDROID_KEY_ALIAS` | Android | key alias inside the keystore |
| `ANDROID_KEY_PASS` | Android | key password (falls back to the store pass) |
| `GOOGLE_PLAY_SERVICE_ACCOUNT` | Android | Play publisher service-account JSON |
| `GOOGLE_SERVICES` | Android | base64 `google-services.json`, for FCM (push) |
| `ASC_API_ID` / `ASC_ISSUER_ID` / `ASC_API_KEY` | iOS | App Store Connect API key (`.p8`, raw or base64) |
| `APPLE_ID` / `APPLE_TEAM_ID` | iOS | developer account and team |
| `MATCH_PASSWORD` | iOS | passphrase for the match certificates repo |
| `MATCH_SSH_KEY` | iOS | deploy key with read access to that repo |
| `DISCORD_WEBHOOK` | both | failure notifications |

Plus one repository **variable**: `MATCH_URL`, the SSH URL of the private
certificates repo (`git@github.com:Hasht-net/ios-match-certs.git`) — the
sibling of `django-files/ios-match-certs`.

### iOS signing

`fastlane match` in `readonly` mode: CI consumes the certificates repo and
never writes to it. Bootstrap it once, locally:

```sh
bundle install
MATCH_URL=git@github.com:Hasht-net/ios-match-certs.git \
  bundle exec fastlane match appstore
```

The Capacitor-generated project ships `CODE_SIGN_STYLE = Automatic`, which
ignores a match profile; `prepare_signing` in `fastlane/Fastfile` pins each
target to manual signing for the run. There are two: the app
(`net.hasht.app`) and its Notification Service Extension
(`net.hasht.app.NotificationService`), each with its own App ID and
`appstore` profile in the match repo. Both App IDs need the App Group
`group.net.hasht.app`; the app's also needs Push Notifications. Re-run
`bundle exec fastlane match appstore` locally after creating the extension's
App ID, so the match repo holds its profile. Versions are passed as `xcargs` build
setting overrides (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`) rather than
edited into `project.pbxproj` — `Info.plist` already resolves both through
`$(...)`, and nothing tracked gets mutated.

`ios/App/App.xcodeproj/xcshareddata/xcschemes/App.xcscheme` is committed because
Xcode autocreates the `App` scheme into `xcuserdata/`, which is gitignored —
CI would find no scheme at all.

### Android signing

`android/app/build.gradle` defines the release signing config **only** when
`HASHT_KEYSTORE_PATH` is set, so a local `assembleRelease` still builds
unsigned without the release secrets. `versionCode`/`versionName` come from
`-PhashtVersionCode` / `-PhashtVersionName`, defaulting to `1` / `1.0`.
`versionName` is the full tag without the `v` (`0.1.0-beta.3`) so the app can
report it; Play only cares about `versionCode`. iOS must stay bare `X.Y.Z`.

Play App Signing holds the real app key; the keystore above is the *upload*
key.

### Before the first run

Both stores need the app to exist before any automation can reach it:

- App Store Connect: create the `net.hasht.app` app record. Guideline 2.1 also
  wants a demo account against a live instance — see "Open questions".
- Google Play: create the app and upload one AAB **by hand**; the publisher API
  refuses the first upload for a package it has never seen.

## Status

| | |
|---|---|
| iOS builds + runs | ✅ verified on iOS 26.5 simulator |
| Android project builds | ✅ (`compileDebugJavaWithJavac`), ❌ not yet run |
| Wizard renders | ✅ verified in simulator |
| `ServerConfig` plugin | ✅ verified — store, launch reload, remote load |
| Loads a live instance in-app | ✅ verified against chat.hasht.net |
| Instance probe (native) | ✅ verified against chat.hasht.net |
| Return to wizard | ⚠️ plugin method ready; awaiting the frontend control |
| Connect buttons (tap-through) | ⚠️ not yet exercised — no touch input via simctl |
| Push (`NativePush`) | ✅ builds on both, decryptors pass RFC 8291 vector; ❌ not yet run on a device (needs the relay deployed, APNs key, Firebase project) |
| Passkey sign-in (`NativeAuth`) | ✅ builds on both; ❌ not yet run on a device |
| Frontend integration | ⚠️ contract drafted, not yet vendored into chat |

### Things the first run taught us

Four traps, all invisible to a green build:

1. **App-target Swift plugins are not auto-registered.** They need
   `bridge?.registerPluginInstance(...)` — hence `MainViewController`.
2. **Capacitor 8's `SceneDelegate` builds `CAPBridgeViewController()` in code**,
   so setting the class in `Main.storyboard` does nothing. The subclass has to
   be wired in `SceneDelegate.swift`.
3. **`cap add ios` generates a legacy Xcode project**, so new `.swift` files
   must be added to `project.pbxproj` to be compiled at all. A build that never
   compiles your file still says BUILD SUCCEEDED.
4. **Without `server.allowNavigation`, the instance opens in Safari** — Capacitor
   treats any origin outside its allowlist as external. Set to `['*']` because
   the instance is user-supplied. Links to other origins are then intercepted
   natively; see "Links to other sites".
5. **Capacitor re-raises the webview across navigations.** A native overlay
   added in `viewDidLoad` is on top at launch and behind the page after the
   first navigation, so it must reclaim the top in `viewDidLayoutSubviews` —
   the symptom is an overlay that only appears after a force-quit. Worth
   knowing before adding any native overlay here.
6. **`env(safe-area-inset-*)` is 0 in this webview**, under every
   `contentInset` mode. The shell publishes the real insets as CSS variables
   from `MainViewController`; see roadmap item 6.
7. **The wizard cannot `fetch` an instance.** It is served from
   `capacitor://localhost`, so every request to an instance is cross-origin and
   no self-hoster will have that scheme in `CORS_ALLOWED_ORIGINS`. Hence
   `ServerConfig.probe` runs natively. Anything else the wizard ever needs from
   an instance has the same constraint — the remote frontend does not, since it
   is same-origin with its own server.

## Roadmap

1. **Verify `UIBackgroundModes: audio` keeps the mic alive** when the app is
   backgrounded during a call. A day's work, and it decides whether the media
   plane can stay in the webview or has to go native — the most expensive
   branch in the plan. WebKit #241480 is still open.
2. Confirm the Capacitor bridge is reachable from the remote origin (the app
   loads it; nothing has called a plugin from there yet). Everything in
   `src/integration/` depends on this.
3. Push: run it on devices. The relay, `NativePush`, the extension and the
   backend side are built; what's left needs accounts and hardware — deploy
   the relay, create the APNs key, Firebase project and the extension's App
   ID/profile, then work through the device matrix (killed / locked /
   foreground, replace, edit, delete, read elsewhere, sign-out, uninstall).
   Measure how reliably iOS delivers background removes.
4. Frontend: a "switch server" control calling `forgetInstance()`, reachable
   both signed-in and signed-out. Closes the gap above.
5. Run passkey sign-in on real devices against chat.hasht.net and a
   self-hosted instance (see "Signing in"), including Android with a browser
   that has no Auth Tab.
6. **Frontend: consume the safe-area variables.** `env(safe-area-inset-*)`
   resolves to 0 in this webview (measured under both `contentInset: 'never'`
   and `'automatic'`), so the frontend's 36 `env()` rules compute to nothing
   and the mobile top bar lands under the status bar. The shell publishes the
   real insets as `--hasht-safe-area-{top,right,bottom,left}`; the frontend
   change is mechanical and PWA-safe:
   `env(safe-area-inset-top)` → `var(--hasht-safe-area-top, env(safe-area-inset-top))`.
7. Badge counts.

## Open questions

- **Background mic** (item 1) — everything else is downstream of it.
- **App Store 4.2.** A remote-loading shell is the shape Apple scrutinises.
  Native surface (push, deep links, the picker) has to carry the "not a
  repackaged website" argument. Guideline 2.1 also requires a demo account
  against a live backend, which means operating a public demo instance
  indefinitely.
- **Plain-HTTP instances.** `NSAllowsLocalNetworking` covers LAN; a public
  HTTP-only instance is currently refused. Probably correct, worth confirming.
