import { registerPlugin } from '@capacitor/core';

export interface SharedFile {
  name: string;
  type: string;
  size: number;
  /** Same-origin path the page fetches the bytes from; valid until `release()`. */
  url: string;
}

export interface SharedContent {
  /** Identifies this share to `release()`. */
  token: string;
  text: string | null;
  files: SharedFile[];
  /** Items the shell couldn't read or that were over its limits. */
  skipped: number;
}

/**
 * Content sent to the app from the system share sheet (Android ACTION_SEND /
 * SEND_MULTIPLE). Called by the remote frontend, not by the bootstrap picker;
 * see `src/integration/hashtNative.ts`.
 */
export interface ShareTargetPlugin {
  /** The pending share, or null. Taking it clears it. */
  take(): Promise<{ share: SharedContent | null }>;
  /** Drops one taken share's copied files once the page has read them. */
  release(options: { token: string }): Promise<void>;
  /** A share arrived while the app was running; call `take()`. */
  addListener(event: 'shareReceived', handler: () => void): Promise<{ remove: () => Promise<void> }>;
}

export const ShareTarget = registerPlugin<ShareTargetPlugin>('ShareTarget');
