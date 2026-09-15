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

/**
 * `auth::native` hands the one-time code back over `hasht://`. On the web this
 * arrives as a redirect; in the shell it arrives as an appUrlOpen event.
 */
export function onDeepLink(handler: (url: URL) => void): void {
  capacitor()?.addListener?.('App', 'appUrlOpen', (data) => {
    const raw = (data as { url?: string }).url;
    if (!raw) return;
    try {
      handler(new URL(raw));
    } catch {
      /* a malformed deep link is not worth surfacing to the user */
    }
  });
}

/** Sign-out returns the shell to the instance picker. */
export async function forgetInstance(): Promise<void> {
  await capacitor()?.Plugins?.ServerConfig?.reset();
}
