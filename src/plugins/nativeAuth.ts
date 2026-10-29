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
   * Signs in to the active server. Opens the browser sheet. Rejects with code `cancelled` if the user backs out.
   *
   * `invite` (alphanumeric, else dropped) prefills the browser's invite field;
   * `register` opens it on Register; `username` and `displayName` fill in its
   * Register form. With `register`, `email`, `birthday` (YYYY-MM-DD) and
   * `birthdayVisibility` fill in the rest; they travel in the URL fragment,
   * never the query, and invalid values are dropped. None of them affects
   * where the code goes.
   *
   * `auth` is the server's `AuthResponse`, passed through untouched.
   */
  signIn(options?: {
    invite?: string;
    register?: boolean;
    username?: string;
    displayName?: string;
    email?: string;
    birthday?: string;
    birthdayVisibility?: 'full' | 'month_day' | 'hidden';
  }): Promise<{ auth: unknown }>;
  /**
   * Auth that a `hasht://signin?server=<origin>` link finished for the active
   * server before loading it. Null when there is none; taking it clears it.
   */
  takePendingAuth(): Promise<{ auth: unknown | null }>;
}

export const NativeAuth = registerPlugin<NativeAuthPlugin>('NativeAuth');
