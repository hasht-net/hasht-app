import { registerPlugin } from '@capacitor/core';

export interface ShortcutConversation {
  /** Channel id. */
  id: string;
  title: string;
  /** The server a channel is in. Appended to the title (iOS) or long label (Android). */
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
   * the previous list. The menu holds four items; "Snooze notifications"
   * (`snooze`, while signed in) takes one. Empty and `snooze: false` clears.
   */
  set(options: { conversations: ShortcutConversation[]; snooze?: boolean }): Promise<void>;
  /**
   * What a shortcut asked for on this entry, or nulls. `action: 'snooze'`
   * means open the snooze setting. Taking it clears it.
   */
  take(): Promise<{ conversationId: string | null; action: 'snooze' | null }>;
  /** A shortcut was used while the page was loaded; call `take()`. */
  addListener(event: 'shortcutOpened', handler: () => void): Promise<{ remove: () => Promise<void> }>;
}

export const AppShortcuts = registerPlugin<AppShortcutsPlugin>('AppShortcuts');
