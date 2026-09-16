# hasht-app

Capacitor mobile shell for [Hasht](https://github.com/Hasht-net) — a thin client that
loads a user-configured server URL. iOS and Android.

Sibling of `hasht-desktop` (Electron). Both wrap the same Vue frontend; neither
contains a copy of it.

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
without navigating — the home for push unregistration later). The shell renders no control of its
own: anything it drew would sit over the chat UI forever, duplicating what the
frontend can present properly in its settings. The frontend calls
`ServerConfig.reset()` (see `forgetInstance()` in `src/integration/`), which
clears the active server — keeping it in the list — and returns to the wizard.

Two platform gestures remain, neither of them chrome:

- **iOS** — left-edge swipe; the wizard stays in the webview's history.
- **Android** — system back, which also clears the active server so the next
  launch does not reload it.

**Open gap:** until the frontend ships that control, a signed-out user on the
wrong instance has only the edge swipe. Offer it from the signed-out screen as
well as from settings.

`ServerConfig` is a small custom plugin (`ios/App/App/ServerConfigPlugin.swift`,
`android/.../ServerConfigPlugin.java`) because Capacitor's `server.url` is
static config and a self-hosted client needs it chosen at runtime.

### Where code runs

Only the picker runs from this repo's assets. Everything after it is the Vue
app served by the instance — so **native integration code lives in the chat
frontend**, not here. `src/integration/hashtNative.ts` is the reference
implementation of that contract, kept in this repo so the app and the API it
depends on version together.

The native bridge (`window.Capacitor`) is injected into whatever origin the
webview loads, so the remote frontend can call plugins directly.

## Setup

```sh
npm install
npm run build          # builds the bootstrap picker only
npx cap sync
npm run ios            # opens Xcode
npm run android        # opens Android Studio
```

Capacitor 8 uses Swift Package Manager on iOS — **no CocoaPods required**.

- Bundle ID: `net.hasht.app`
- Deep link scheme: `hasht://` (consumed by `auth::native`)
- iOS declares `UIBackgroundModes: audio` — see "Open questions" below.

## Releasing

Two workflows, mirroring `Hasht-net/hasht-chat`:

- **`.github/workflows/draft.yaml`** — every push to `main` recreates a single
  draft release with freshly generated notes and the next version
  (`v0.1.0-beta.1` → `v0.1.0-beta.2`). Nothing ships. Publishing is manual: edit
  the draft in the Releases UI and hit publish. To cut a stable version, change
  the draft's tag to `v0.1.0` and clear the prerelease flag before publishing.

- **`.github/workflows/release.yaml`** — runs on publish, one job per platform
  so both binaries share a single build number (`github.run_number`). Stores
  reject a build number that goes backwards, and split workflows would drift.

| release | iOS | Android |
|---|---|---|
| prerelease `v0.1.0-beta.3` | TestFlight | Play `internal`, rolled out |
| stable `v0.1.0` | App Store Connect version, **not** submitted | Play `production`, **draft** |

Neither store submission is automatic — the last step is always a human. Both
jobs also attach their artifact (`.ipa`, `.apk`) to the GitHub release, so a
self-hoster can sideload the exact build that went to the stores.

`workflow_dispatch` rebuilds an existing tag (`tag` input) and can target one
platform (`platforms: ios | android | both`) to retry a single leg.

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
| `GOOGLE_SERVICES` | Android | base64 `google-services.json`, for FCM |
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
ignores a match profile; `prepare_signing` in `fastlane/Fastfile` pins the
target to manual signing for the run. Versions are passed as `xcargs` build
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
| Push | ❌ not started — blocked on the relay |
| Deep links / auth handoff | ⚠️ manifest + Info.plist wired, no handler |
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
   the instance is user-supplied; see the TODO in `capacitor.config.ts`.
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
3. Push: build the APNs relay (separate repo), add a backend endpoint for
   native device tokens, add a Notification Service Extension here to decrypt
   the aes128gcm payload.
4. Frontend: a "switch server" control calling `forgetInstance()`, reachable
   both signed-in and signed-out. Closes the gap above.
5. Wire the `hasht://` deep link into `auth::native`, using
   `ASWebAuthenticationSession` — note `@capacitor/browser` is
   SFSafariViewController and is *not* the right primitive for this.
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
