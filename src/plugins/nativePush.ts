import type { PluginListenerHandle } from '@capacitor/core';
import { registerPlugin } from '@capacitor/core';

/**
 * Native push through the Hasht push relay (`Hasht-net/hasht-push-relay`).
 *
 * The shell has no Web Push — WKWebView has no PushManager and Android's
 * WebView no Push API — so this stands in for `PushManager.subscribe()`. It
 * returns the same triple a browser subscription has: the instance stores it
 * via `/api/push/subscribe` and sends ordinary, end-to-end encrypted Web Push
 * to the relay, which forwards ciphertext to APNs/FCM. The device decrypts.
 *
 * Each instance gets its own registration and keypair, keyed by the calling
 * page's origin. Called by the remote frontend; see
 * `src/integration/hashtNative.ts`.
 */
export interface NativePushPlugin {
  /** Opens this app’s notification settings in the system Settings app. */
  openSettings(): Promise<void>;
  /**
   * Asks for notification permission, then registers this instance with the
   * relay (or refreshes its existing registration). Rejects with code
   * `denied` if the user refused, `unavailable` if this build can't receive
   * push (Android without Firebase config), `relay` if the relay failed.
   *
   * `prompt: false` never shows the permission dialog — for re-registering
   * on load — and rejects `denied` unless permission is already granted.
   */
  register(options: {
    relayUrl: string;
    prompt?: boolean;
  }): Promise<{ endpoint: string; p256dh: string; auth: string }>;
  /** Drops this instance's registration, keys and delivered notifications. */
  unregister(): Promise<void>;
  /** Reconcile delivered notifications through server read timestamps in epoch microseconds. */
  reconcileReads(options: { reads: { tag: string; through: number }[] }): Promise<void>;
  /** Withdraws every delivered message notification for a channel. */
  clearChannel(options: { tag: string }): Promise<void>;
  /** Android lost queued events; acknowledge after authenticated unread state loads. */
  recoveryNeeded(): Promise<{ needed: boolean; generation: number; origin: string }>;
  completeRecovery(options: { generation: number; origin: string }): Promise<void>;
  /**
   * A notification for this instance was tapped. `url` is the path from the
   * push payload. Taps on another instance's notification switch servers
   * natively instead, and arrive there through this same event.
   */
  addListener(
    eventName: 'notificationTapped',
    listener: (event: { url: string }) => void,
  ): Promise<PluginListenerHandle>;
}

export const NativePush = registerPlugin<NativePushPlugin>('NativePush');
