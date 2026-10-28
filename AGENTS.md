# AGENTS.md

Capacitor mobile shell for Hasht (iOS + Android). It loads the frontend from a
user-configured instance.

## Layout

- `index.html`, `src/bootstrap.ts`, `src/bootstrap.css` — the instance picker.
  Its colors, radii and font (Manrope, bundled) are copied from hasht-chat's
  `frontend/src/style.css` and sign-in screen; keep them in step.
  The main chat UI comes from that instance. The iOS Share Extension ships
  a native destination picker.
- `src/plugins/serverConfig.ts`, `src/plugins/nativeAuth.ts` — TS interfaces
  for the custom `ServerConfig` and `NativeAuth` plugins.
- `src/integration/hashtNative.ts` — the native-bridge contract for the *chat
  frontend* to vendor. Nothing here imports it; it lives here so the app and
  the API it depends on version together.
- `capacitor.config.ts` — no static `server.url`; the instance is runtime-chosen.
- `ios/App/App/ServerConfigPlugin.swift`, `android/.../ServerConfigPlugin.java`
  — the native halves of `ServerConfig`.
- `ios/App/App/DeepLinks.swift`, `android/.../DeepLinks.java` — which saved
  server a link opens in (pure, tested). Keep the twins in step. See README,
  "Links into the app".
- `ios/App/App/NativeAuthPlugin.swift`, `android/.../NativeAuthPlugin.java` —
  passkey sign-in via the browser handoff. See README, "Signing in".
- `NativePush` — push through the relay. See README, "Push".
  `src/plugins/nativePush.ts`; iOS `App/NativePushPlugin.swift`, the
  `NotificationService` extension target, and `ios/App/Shared/WebPush.swift`
  (compiled into both targets); Android `NativePushPlugin`,
  `HashtMessagingService`, `PushStore`, `PushRelay`, `PushNotifications`,
  `WebPushCrypto`.
- `android/.../CallSessionPlugin.java`, `OngoingCallService.java` — Android-only;
  keeps the mic alive during a backgrounded call. See README, "Calls in the
  background".
- `ShareTarget` — the system share sheet target. `src/plugins/shareTarget.ts`;
  Android `ShareTargetPlugin` (copies shared streams to cache, served to the
  page under `/_hasht_share_/<token>/` by `HashtWebViewClient`). iOS uses
  `ShareExtension/` for a native DM/channel picker and direct uploads, with
  `Shared/ShareSession.swift` in App and ShareExtension and
  `App/ShareSessionSync.swift` to mirror the active session into the Keychain.
- `AppShortcuts` — the app icon's long-press menu. `src/plugins/appShortcuts.ts`;
  iOS `App/AppShortcutsPlugin.swift` (items handled in `SceneDelegate`);
  Android `AppShortcutsPlugin`, `ShortcutActivity`. See README, "Home-screen
  quick actions".
- `.github/workflows/draft.yaml` — rolling draft release on `main`, same as
  `hasht-chat`. `.github/workflows/release.yaml` — on publish, one job per
  platform sharing a single build number. `fastlane/` is the iOS half of that;
  Android is Gradle plus the Play publisher API. See README, "Releasing".

## Conventions

- The chat frontend is never bundled here. If you find yourself adding a build
  step that pulls `hasht-chat/frontend`, the architecture changed — update the README.
- `limitsNavigationsToAppBoundDomains` must stay `false`. App-Bound Domains
  needs a fixed ≤10-domain list in Info.plist, which a self-hosted client cannot
  satisfy.
- Native platform folders (`ios/`, `android/`) are committed, so hand-edits to
  `Info.plist` / `AndroidManifest.xml` survive. `npx cap sync` does not clobber
  them, but re-running `cap add` would.
- Both release jobs start with the same `npm ci && npm run build && npx cap
  sync <platform>`. Anything that has to happen before a native build belongs in
  that step on *both* platforms, or the two binaries stop matching.
- Release versions are never written into tracked files: iOS gets `xcargs`
  overrides, Android gets `-PhashtVersion*`. A workflow that starts committing
  a version bump has broken that rule.
- New iOS files go in `project.pbxproj` for every target that needs them —
  `Shared/WebPush.swift` is in both App and NotificationService. The
  extension's `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` must match the
  app's; the release `xcargs` override both.
- `ios/.../xcshareddata/xcschemes/App.xcscheme` is committed on purpose — Xcode
  only autocreates it under the gitignored `xcuserdata/`, leaving CI with no
  scheme.
- Comments explain *why*, sparingly.

## Verify

```sh
npx tsc --noEmit
npm run build
scripts/test-webpush-ios.sh
scripts/test-share-ios.sh
scripts/test-deeplinks-ios.sh
cd android && ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest
cd ios/App && xcodebuild -scheme App -destination 'generic/platform=iOS' \
  -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## Related

- `hasht-net/hasht-chat` — backend + Vue frontend. `backend/src/push/` (Web Push,
  aes128gcm), `backend/src/auth/native.rs` (the `hasht://` handoff).
- `hasht-net/hasht-desktop` — Electron shell; same thin-client idea, and the
  precedent for the native-bridge pattern (`src/main/screenShare.ts`,
  `src/main/nativeAuth.ts`).
- `hasht-net/hasht-push-relay` — the push relay `NativePush` registers with
  (Rust; Web Push in, APNs/FCM out).
