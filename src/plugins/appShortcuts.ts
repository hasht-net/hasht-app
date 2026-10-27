import { registerPlugin } from '@capacitor/core';

export interface ShortcutConversation {
  /** Channel id. */
  id: string;
  title: string;
  /** iOS shows it under the title; Android appends it to the long label. */
  subtitle?: string;
  kind: 'dm' | 'group' | 'channel';
}

/**
 * The app icon's long-press menu (iOS Quick Actions, Android launcher
 * shortcuts). Called by the remote frontend, not by the bootstrap picker; see
 * `src/integration/hashtNative.ts`.
 */
export interface AppShortcutsPlugin {
  /**
   * Recent conversations, most recent first, for the active entry. Replaces
   * the previous list; the shell shows up to four, three when it also offers
   * "Switch server". Empty clears.
   */
  set(options: { conversations: ShortcutConversation[] }): Promise<void>;
  /** The conversation a shortcut opened on this entry, or null. Taking it clears it. */
  take(): Promise<{ conversationId: string | null }>;
  /** A shortcut was used while the page was loaded; call `take()`. */
  addListener(event: 'shortcutOpened', handler: () => void): Promise<{ remove: () => Promise<void> }>;
}

export const AppShortcuts = registerPlugin<AppShortcutsPlugin>('AppShortcuts');
