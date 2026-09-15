import { registerPlugin } from '@capacitor/core';

export interface ServerEntry {
  id: string;
  url: string;
  /** Defaults to the hostname when the user doesn't give one. */
  name: string;
}

/**
 * The servers the user has added, and which one the webview is pointed at.
 *
 * Capacitor's `server.url` is static config, so a user-chosen instance can
 * only be applied natively. Mirrors `hasht-desktop`'s `serverStore.ts` so the
 * two shells behave the same; this holds only "which servers has the user
 * added" — never credentials, which stay in each origin's own cookie jar.
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
  list(): Promise<{ servers: ServerEntry[]; activeId: string | null }>;
  /** Adds, makes active, and loads it. */
  add(options: { url: string; name?: string }): Promise<{ server: ServerEntry }>;
  /** Switches to an already-added server and loads it. */
  connect(options: { id: string }): Promise<void>;
  remove(options: { id: string }): Promise<void>;
  rename(options: { id: string; name: string }): Promise<void>;
  /** Back to the wizard without forgetting anything — sign-out, dead instance. */
  reset(): Promise<void>;
}

export const ServerConfig = registerPlugin<ServerConfigPlugin>('ServerConfig');
