import { registerPlugin } from '@capacitor/core';

export interface ServerEntry {
  id: string;
  url: string;
  /** Defaults to the hostname when the user doesn't give one. */
  name: string;
  /** Who the page last reported signed in here (`setAccount`); null when signed out or unknown. */
  username?: string | null;
  displayName?: string | null;
}

/**
 * The servers the user has added, and which one the webview is pointed at.
 *
 * Capacitor's `server.url` is static config, so a user-chosen instance can
 * only be applied natively. Mirrors `hasht-desktop`'s `serverStore.ts` so the
 * two shells behave the same; this holds only "which servers has the user
 * added". Each entry has separate sign-in storage and push registration.
 */
export interface ServerConfigPlugin {
  /**
   * Confirms an origin is a Hasht instance, and reports its name.
   *
   * Runs natively: the wizard is served from `capacitor://localhost`, so a
   * `fetch` from it is cross-origin and no instance will have that scheme in
   * its `CORS_ALLOWED_ORIGINS`. Native HTTP has no such restriction.
   */
  probe(options: { url: string }): Promise<{ name: string }>;
  /** `defaultId`: where a link goes when several entries share its origin. */
  list(): Promise<{ servers: ServerEntry[]; activeId: string | null; defaultId: string | null }>;
  /** Sets or clears (`id: null`) the default entry for links. Cleared on `remove`. */
  setDefault(options: { id: string | null }): Promise<void>;
  /**
   * The page reports who is signed in on the active entry, for the picker and
   * the link chooser. Names only, never a token; nulls clear it, as `signOut` does.
   */
  setAccount(options: { username: string | null; displayName: string | null }): Promise<void>;
  /** Adds a fresh account entry, makes it active, and loads it. Duplicate URLs are allowed. */
  add(options: { url: string; name?: string }): Promise<{ server: ServerEntry }>;
  /** Switches to an already-added server and loads it. */
  connect(options: { id: string }): Promise<void>;
  remove(options: { id: string }): Promise<void>;
  rename(options: { id: string; name: string }): Promise<void>;
  /**
   * The frontend signed the user out. Drops session-scoped native state
   * (delivered notifications, badge, and later the push registration); does
   * not navigate.
   */
  signOut(): Promise<void>;
  /** Opens a file in the native browser, even when its URL belongs to this instance. */
  openAttachment(options: { url: string }): Promise<void>;
  /** Streams a file to the native save/share UI. Cancelling resolves without an error. */
  saveAttachment(options: { url: string; filename?: string }): Promise<{ cancelled?: boolean }>;
  /** Opens this instance’s security settings in a WebAuthn-capable browser. */
  openSecurityManagement(options: { ticket: string }): Promise<void>;
  openSecuritySettings(options: { userId: string }): Promise<void>;
  /** Paints the status and navigation bars to match a page's resolved theme. */
  setTheme(options: { theme: 'dark' | 'light'; color: string }): Promise<void>;
  /** Back to the wizard without forgetting anything — a dead instance, or an explicit switch. */
  reset(): Promise<void>;
  /**
   * Where links that leave the instance open. `in-app` (the default) is a
   * Custom Tab / Safari sheet over the app; `system` is the default browser.
   */
  getLinkMode(): Promise<{ mode: LinkMode }>;
  setLinkMode(options: { mode: LinkMode }): Promise<void>;
}

export type LinkMode = 'in-app' | 'system';

export const ServerConfig = registerPlugin<ServerConfigPlugin>('ServerConfig');
