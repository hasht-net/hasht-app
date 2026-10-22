/**
 * Reference module for the *chat frontend*, not for this app's bootstrap.
 *
 * Because the shell remote-loads the instance, everything after the picker
 * runs in the Vue app served by the user's server — so that is where the
 * native bridge has to be called from. Vendor or port this into
 * `frontend/src/` in the chat repo; it is kept here so the app and the
 * contract it depends on version together.
 *
 * The native bridge (`window.Capacitor`) is injected into whatever origin the
 * webview loads, so the remote frontend can reach plugins without this repo's
 * assets being involved at all.
 *
 * Android back has no API: the shell dispatches an Escape keydown and treats
 * it as handled only if the page cancels it. A handler that closes something
 * on Escape must call `preventDefault()`; one that doesn't must not.
 */

type PluginRegistry = Record<string, Record<string, (...args: unknown[]) => Promise<unknown>>>;

interface CapacitorGlobal {
  isNativePlatform?: () => boolean;
  getPlatform?: () => string;
  Plugins?: PluginRegistry;
}

function capacitor(): CapacitorGlobal | undefined {
  return (window as unknown as { Capacitor?: CapacitorGlobal }).Capacitor;
}

export function isNativeShell(): boolean {
  return capacitor()?.isNativePlatform?.() === true;
}

export function nativePlatform(): 'ios' | 'android' | 'web' {
  const platform = capacitor()?.getPlatform?.();
  return platform === 'ios' || platform === 'android' ? platform : 'web';
}

/**
 * The relay the app's builds are registered with. Its host has to be in the
 * instance's push endpoint allowlist (`push::endpoint` in the backend); an
 * instance too old to know it answers 400 on subscribe.
 */
export const HASHT_PUSH_RELAY = 'https://push.hasht.net';

export interface NativePushSubscription {
  endpoint: string;
  p256dh: string;
  auth: string;
}

/**
 * Web Push is unavailable in the shell — WKWebView has no PushManager, and
 * Android's WebView has no Push API — so the native transport replaces it
 * rather than supplementing it. Guard the existing `PushManager.subscribe()`
 * path on `!isNativeShell()` and call `subscribeNativePush()` instead.
 *
 * Null when the user refused permission or this build can't receive push.
 */
export async function registerNativePush(
  { prompt = true, relayUrl = HASHT_PUSH_RELAY }: { prompt?: boolean; relayUrl?: string } = {},
): Promise<NativePushSubscription | null> {
  const push = capacitor()?.Plugins?.NativePush;
  if (!push) return null;
  try {
    return (await push.register({ relayUrl, prompt })) as NativePushSubscription;
  } catch (error) {
    const code = (error as { code?: string })?.code;
    if (code === 'denied' || code === 'unavailable') return null;
    throw error;
  }
}

export type NativePushResult = 'subscribed' | 'declined' | 'server-too-old';

/**
 * Registers with the relay and stores the result on this instance, exactly
 * where a browser subscription goes. `post` is the frontend's own authenticated
 * call to `POST /api/push/subscribe`; it must reject with the response status
 * on failure. Safe to call on every load: the native side reuses the existing
 * registration and the backend upserts by endpoint.
 */
export async function subscribeNativePush(
  post: (body: { endpoint: string; p256dh_key: string; auth_key: string }) => Promise<unknown>,
  options: { prompt?: boolean; relayUrl?: string } = {},
): Promise<NativePushResult> {
  const sub = await registerNativePush(options);
  if (!sub) return 'declined';
  try {
    await post({ endpoint: sub.endpoint, p256dh_key: sub.p256dh, auth_key: sub.auth });
    return 'subscribed';
  } catch (error) {
    // The endpoint allowlist predates the relay: tell the user the server
    // needs an upgrade rather than failing silently.
    if ((error as { status?: number })?.status === 400) {
      await unregisterNativePush();
      return 'server-too-old';
    }
    throw error;
  }
}

/**
 * On sign-out, alongside the instance's own unsubscribe. (`ServerConfig.signOut`
 * also does this natively, as a backstop.)
 */
export async function unregisterNativePush(): Promise<void> {
  await capacitor()?.Plugins?.NativePush?.unregister();
}

/**
 * The page read a channel: withdraw its notifications from the lock screen /
 * shade too. The native twin of `closeChannelNotifications()` — call both.
 * `tag` is the payload tag, `channel:<id>`.
 */
export async function clearNativeChannel(tag: string): Promise<void> {
  if (!isNativeShell()) return;
  await capacitor()?.Plugins?.NativePush?.clearChannel({ tag });
}

/** After an authenticated read snapshot, repair missed background recalls. */
export async function reconcileNativeReads(reads: { tag: string; through: number }[]): Promise<void> {
  if (!isNativeShell()) return;
  await capacitor()?.Plugins?.NativePush?.reconcileReads?.({ reads });
}

/** Android overflow recovery. Old shells and iOS have no recovery callback. */
export async function nativePushRecovery(): Promise<{ origin: string; generation: number } | null> {
  const recovery = await capacitor()?.Plugins?.NativePush?.recoveryNeeded?.() as
    | { needed?: boolean; origin?: string; generation?: number } | undefined;
  if (!recovery?.needed || !recovery.origin || !recovery.generation) return null;
  return { origin: recovery.origin, generation: recovery.generation };
}

/** Call only after this instance's authenticated unread/channel state loads successfully. */
export async function completeNativePushRecovery(recovery: { origin: string; generation: number }): Promise<void> {
  await capacitor()?.Plugins?.NativePush?.completeRecovery?.(recovery);
}

/**
 * Tapping a notification has to land on the message it is about. `url` is
 * the payload's path on this instance. Register early: a tap that launched
 * the app is held until the first listener is added.
 */
export function onNotificationTap(handler: (url: string) => void): void {
  // Through the plugin's own addListener: that is the call that releases an
  // event the native side retained.
  void capacitor()?.Plugins?.NativePush?.addListener('notificationTapped', (data: unknown) => {
    const url = (data as { url?: string }).url;
    if (url) handler(url);
  });
}

/** Whether this shell can hand passkey sign-in to the browser (`NativeAuth`). */
export function canSignInWithBrowser(): boolean {
  return !!capacitor()?.Plugins?.NativeAuth;
}

/**
 * Passkey sign-in via the `auth::native` browser handoff — the webview itself
 * can't run WebAuthn for a self-hosted origin. The shell opens the browser
 * sheet and redeems the code; this resolves with the
 * server's `AuthResponse`, or null if the user cancelled.
 */
export async function signInWithBrowser(
  options: { invite?: string; register?: boolean; username?: string; displayName?: string } = {},
): Promise<unknown | null> {
  try {
    const { auth } = (await capacitor()?.Plugins?.NativeAuth?.signIn(options)) as { auth: unknown };
    return auth;
  } catch (error) {
    if ((error as { code?: string })?.code === 'cancelled') return null;
    throw error;
  }
}

/**
 * Auth finished by a `hasht://signin?server=<origin>` link (the web app's
 * "Sign in to the Hasht app" setting), waiting for the page to adopt it on
 * load. Null when there is none.
 */
export async function takePendingAuth(): Promise<unknown | null> {
  try {
    const result = (await capacitor()?.Plugins?.NativeAuth?.takePendingAuth()) as
      | { auth: unknown | null }
      | undefined;
    return result?.auth ?? null;
  } catch {
    return null;
  }
}

/**
 * Android silences the WebView's mic soon after the app is backgrounded unless
 * a microphone foreground service is running, so mirror every call's lifetime
 * here: start once the call has the mic (the runtime permission must already
 * be granted) and while the page is visible, stop on every way a call ends.
 * The ongoing notification it shows is also what the Play declaration for
 * FOREGROUND_SERVICE_MICROPHONE relies on — don't start it outside a call.
 *
 * No-op elsewhere: iOS keeps the mic via `UIBackgroundModes: audio`.
 */
export async function startCallSession(title?: string): Promise<void> {
  if (nativePlatform() !== 'android') return;
  await capacitor()?.Plugins?.CallSession?.start({ title });
}

export async function endCallSession(): Promise<void> {
  if (nativePlatform() !== 'android') return;
  await capacitor()?.Plugins?.CallSession?.stop();
}

/**
 * Returns the shell to the instance wizard. The shell shows no server-switching
 * control of its own, so the frontend owns this entirely — offer it both from
 * settings and from the signed-out screen, or a wrong instance is a dead end.
 */
export async function forgetInstance(): Promise<void> {
  await capacitor()?.Plugins?.ServerConfig?.reset();
}

/**
 * Who is signed in, for the shell's server list and link chooser. Call after
 * sign-in, session restore and a rename; `null` on sign-out. Older shells lack
 * it, so failures are ignored.
 */
export async function setAccount(account: { username: string; displayName: string } | null): Promise<void> {
  try {
    await capacitor()?.Plugins?.ServerConfig?.setAccount({
      username: account?.username ?? null,
      displayName: account?.displayName ?? null,
    });
  } catch {
    // Older shell.
  }
}

export type LinkMode = 'in-app' | 'system';

/**
 * Links to other sites open outside the chat webview — in a sheet over the app
 * (`in-app`, the default) or the default browser (`system`). Offer this in
 * settings when `isNativeShell()`; null where the shell predates it.
 */
export async function getLinkMode(): Promise<LinkMode | null> {
  try {
    const result = (await capacitor()?.Plugins?.ServerConfig?.getLinkMode()) as
      | { mode: LinkMode }
      | undefined;
    return result?.mode ?? null;
  } catch {
    return null;
  }
}

export async function setLinkMode(mode: LinkMode): Promise<void> {
  await capacitor()?.Plugins?.ServerConfig?.setLinkMode({ mode });
}

/**
 * The shell's own version (`2026.10.02-beta.0` on Android; iOS reports the
 * bare store version), for About screens that would otherwise show only the
 * server's. Null outside the shell.
 */
export async function nativeAppVersion(): Promise<{ version: string; build: string } | null> {
  try {
    const info = (await capacitor()?.Plugins?.App?.getInfo()) as
      | { version?: string; build?: string }
      | undefined;
    return info?.version ? { version: info.version, build: info.build ?? '' } : null;
  } catch {
    return null;
  }
}

export interface NativeShare {
  text: string | null;
  files: File[];
  skipped: number;
}

/**
 * Content sent to the app from the system share sheet. Null when there is
 * none or the shell predates `ShareTarget`. Call on load and on every
 * `onNativeShare`; each share is returned once. Files are fetched from a
 * same-origin path the shell serves only until this resolves.
 */
export async function takeNativeShare(): Promise<NativeShare | null> {
  const plugin = capacitor()?.Plugins?.ShareTarget;
  if (!plugin) return null;
  const { share } = (await plugin.take()) as {
    share: { token: string; text: string | null; skipped: number; files: { name: string; type: string; url: string }[] } | null;
  };
  if (!share) return null;
  try {
    const files = await Promise.all(share.files.map(async (f) => {
      const response = await fetch(f.url);
      if (!response.ok) throw new Error(`shared file ${response.status}`);
      return new File([await response.blob()], f.name, { type: f.type });
    }));
    return { text: share.text, files, skipped: share.skipped };
  } finally {
    // By token: a share taken while this one was being read has its own files.
    await plugin.release({ token: share.token });
  }
}

export function onNativeShare(handler: () => void): void {
  void capacitor()?.Plugins?.ShareTarget?.addListener('shareReceived', handler);
}

/** Opens Hasht's system notification settings; rejects on unsupported older shells. */
export async function openNativeNotificationSettings(): Promise<void> {
  const push = capacitor()?.Plugins?.NativePush;
  if (!isNativeShell() || !push?.openSettings) {
    throw new Error('Update the Hasht app to open notification settings.');
  }
  await push.openSettings();
}
