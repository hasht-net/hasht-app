import { registerPlugin } from '@capacitor/core';

/**
 * Passkey sign-in handed off to the OS's browser sign-in sheet
 * (`ASWebAuthenticationSession` on iOS; an Auth Tab or Custom Tab on Android).
 *
 * Neither platform's webview can run a WebAuthn ceremony for an arbitrary
 * self-hosted origin, so the instance's own approve page runs in the browser
 * and hands a one-time code back over `hasht://auth` — the `auth::native` flow
 * in the chat backend. The PKCE verifier stays native. Called by the remote
 * frontend, not by the bootstrap picker; see `src/integration/hashtNative.ts`.
 */
export interface NativeAuthPlugin {
  /**
   * Signs in to the active server. Shows the match code natively, then opens
   * the browser sheet. Rejects with code `cancelled` if the user backs out.
   *
   * `auth` is the server's `AuthResponse`, passed through untouched.
   */
  signIn(): Promise<{ auth: unknown }>;
  /**
   * Auth that a `hasht://signin?server=<origin>` link finished for the active
   * server before loading it. Null when there is none; taking it clears it.
   */
  takePendingAuth(): Promise<{ auth: unknown | null }>;
}

export const NativeAuth = registerPlugin<NativeAuthPlugin>('NativeAuth');
