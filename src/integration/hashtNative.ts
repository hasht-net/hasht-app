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
 */

type PluginRegistry = Record<string, Record<string, (...args: unknown[]) => Promise<unknown>>>;

interface CapacitorGlobal {
  isNativePlatform?: () => boolean;
  getPlatform?: () => string;
  Plugins?: PluginRegistry;
  addListener?: (plugin: string, event: string, cb: (data: unknown) => void) => void;
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
 * Web Push is unavailable in the shell — WKWebView has no PushManager, and
 * Android's WebView has no Push API — so the native transport replaces it
 * rather than supplementing it. Guard the existing
 * `PushManager.subscribe()` path on `!isNativeShell()`.
 *
 * TODO(relay): needs a backend endpoint that accepts an APNs/FCM device token
 * alongside a client-generated p256dh/auth keypair, and stores the relay URL
 * as the subscription `endpoint` so `push::send_to_users` keeps working
 * unchanged. Blocked on the relay existing.
 */
export async function registerNativePush(): Promise<string | null> {
  const push = capacitor()?.Plugins?.PushNotifications;
  if (!push) return null;

  const permission = (await push.requestPermissions()) as { receive?: string };
  if (permission.receive !== 'granted') return null;

  return new Promise((resolve) => {
    capacitor()?.addListener?.('PushNotifications', 'registration', (data) => {
      resolve((data as { value?: string }).value ?? null);
    });
    void push.register();
  });
}

/** Tapping a notification has to land on the message it is about. */
export function onNotificationTap(handler: (url: string) => void): void {
  capacitor()?.addListener?.('PushNotifications', 'pushNotificationActionPerformed', (data) => {
    const url = (data as { notification?: { data?: { url?: string } } }).notification?.data?.url;
    if (url) handler(url);
  });
}

/** Whether this shell can hand passkey sign-in to the browser (`NativeAuth`). */
export function canSignInWithBrowser(): boolean {
  return !!capacitor()?.Plugins?.NativeAuth;
}

/**
 * Passkey sign-in via the `auth::native` browser handoff — the webview itself
 * can't run WebAuthn for a self-hosted origin. The shell shows the match code,
 * opens the browser sheet and redeems the code; this resolves with the
 * server's `AuthResponse`, or null if the user cancelled.
 */
export async function signInWithBrowser(): Promise<unknown | null> {
  try {
    const { auth } = (await capacitor()?.Plugins?.NativeAuth?.signIn()) as { auth: unknown };
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
