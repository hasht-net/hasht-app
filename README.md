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

A wrong instance must never be a dead end, and the escape cannot depend on the
remote frontend cooperating — before sign-in there is nothing to cooperate with.
So the shell owns one:

- **iOS** — a "‹ Servers" pill, top-leading. It stays put rather than
  auto-hiding: an escape the user cannot see is not an escape. Left-edge swipe
  also works; the wizard stays in the webview's history.
- **Android** — system back, which also clears the active server so the next
  launch does not reload it.

Both clear the active server but keep it in the list. Once the frontend offers
switching in its own settings, it should call `hideShellSwitcher()` after
sign-in so there aren't two — and `forgetInstance()` to switch.

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

## Status

| | |
|---|---|
| iOS builds + runs | ✅ verified on iOS 26.5 simulator |
| Android project builds | ✅ (`compileDebugJavaWithJavac`), ❌ not yet run |
| Wizard renders | ✅ verified in simulator |
| `ServerConfig` plugin | ✅ verified — store, launch reload, remote load |
| Loads a live instance in-app | ✅ verified against chat.hasht.net |
| Instance probe (native) | ✅ verified against chat.hasht.net |
| Escape back to the wizard | ✅ renders and persists (iOS); ⚠️ tap-through not exercised |
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
5. **The wizard cannot `fetch` an instance.** It is served from
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
4. Wire the `hasht://` deep link into `auth::native`, using
   `ASWebAuthenticationSession` — note `@capacitor/browser` is
   SFSafariViewController and is *not* the right primitive for this.
5. Keyboard/safe-area: the frontend's `useViewportKeyboard` assumes Safari
   behavior and will need adjusting.
6. Badge counts.

## Open questions

- **Background mic** (item 1) — everything else is downstream of it.
- **App Store 4.2.** A remote-loading shell is the shape Apple scrutinises.
  Native surface (push, deep links, the picker) has to carry the "not a
  repackaged website" argument. Guideline 2.1 also requires a demo account
  against a live backend, which means operating a public demo instance
  indefinitely.
- **Plain-HTTP instances.** `NSAllowsLocalNetworking` covers LAN; a public
  HTTP-only instance is currently refused. Probably correct, worth confirming.
