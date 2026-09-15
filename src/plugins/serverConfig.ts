import { registerPlugin } from '@capacitor/core';

/**
 * Runtime control over which instance the webview points at.
 *
 * Capacitor's `server.url` is static config, so a user-chosen instance has to
 * be applied natively: `connect` persists the URL and reloads the webview at
 * it; `reset` returns to the bundled bootstrap picker (used on sign-out and
 * when an instance becomes unreachable).
 */
export interface ServerConfigPlugin {
  /** The instance currently configured, or `{ url: null }` on first run. */
  get(): Promise<{ url: string | null }>;
  connect(options: { url: string }): Promise<void>;
  reset(): Promise<void>;
}

export const ServerConfig = registerPlugin<ServerConfigPlugin>('ServerConfig');
