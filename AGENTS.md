# AGENTS.md

Capacitor mobile shell for Hasht (iOS + Android). Thin client — remote-loads the
frontend from a user-configured instance.

## Layout

- `index.html`, `src/bootstrap.ts`, `src/bootstrap.css` — the instance picker.
  The **only** UI this repo ships; everything after connecting is the remote
  Vue app.
- `src/plugins/serverConfig.ts` — TS interface for the custom `ServerConfig`
  plugin.
- `src/integration/hashtNative.ts` — the native-bridge contract for the *chat
  frontend* to vendor. Nothing here imports it; it lives here so the app and
  the API it depends on version together.
- `capacitor.config.ts` — no static `server.url`; the instance is runtime-chosen.
- `ios/App/App/ServerConfigPlugin.swift`, `android/.../ServerConfigPlugin.java`
  — the native halves of `ServerConfig`.

## Conventions

- The chat frontend is never bundled here. If you find yourself adding a build
  step that pulls `chat/frontend`, the architecture changed — update the README.
- `limitsNavigationsToAppBoundDomains` must stay `false`. App-Bound Domains
  needs a fixed ≤10-domain list in Info.plist, which a self-hosted client cannot
  satisfy.
- Native platform folders (`ios/`, `android/`) are committed, so hand-edits to
  `Info.plist` / `AndroidManifest.xml` survive. `npx cap sync` does not clobber
  them, but re-running `cap add` would.
- Comments explain *why*, sparingly.

## Verify

```sh
npx tsc --noEmit
npm run build
cd android && ./gradlew :app:compileDebugJavaWithJavac
cd ios/App && xcodebuild -scheme App -destination 'generic/platform=iOS' \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## Related

- `Hasht-net/chat` — backend + Vue frontend. `backend/src/push/` (Web Push,
  aes128gcm), `backend/src/auth/native.rs` (the `hasht://` handoff).
- `Hasht-net/hasht-desktop` — Electron shell; same thin-client idea, and the
  precedent for the native-bridge pattern (`src/main/screenShare.ts`,
  `src/main/nativeAuth.ts`).
- Push relay — separate repo, not yet created.
