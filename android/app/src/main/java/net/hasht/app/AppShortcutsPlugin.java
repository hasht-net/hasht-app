package net.hasht.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher icon's long-press menu: the conversations the page last
 * reported, "Snooze notifications" when the page offers it, and "Switch
 * server" when there is more than one.
 *
 * The list belongs to the entry that set it, so a shortcut switches to that
 * entry first. The page collects the conversation with {@code take()}, the
 * same way it collects a share. Shortcuts go through {@link ShortcutActivity}.
 */
@CapacitorPlugin(name = "AppShortcuts")
public class AppShortcutsPlugin extends Plugin {
    static final String ACTION = "net.hasht.app.SHORTCUT";
    static final String EXTRA_ENTRY = "net.hasht.app.shortcut.entry";
    static final String EXTRA_CONVERSATION = "net.hasht.app.shortcut.conversation";
    static final String EXTRA_SERVERS = "net.hasht.app.shortcut.servers";
    static final String EXTRA_SNOOZE = "net.hasht.app.shortcut.snooze";
    private static final String PREFS = "hasht_shortcuts";
    private static final String KEY_STORED = "stored";
    // Launchers show four or five; keep the same menu on both platforms.
    private static final int LIMIT = 4;

    /** Static: switching servers recreates the activity and this plugin with it. */
    private static String pendingEntry;
    private static String pendingConversation;
    private static boolean pendingSnooze;

    @PluginMethod
    public void set(PluginCall call) {
        JSONObject active = ServerConfigPlugin.activeServer(getContext());
        if (active == null) {
            call.resolve();
            return;
        }
        JSArray raw = call.getArray("conversations", new JSArray());
        JSONArray conversations = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null || item.optString("id").isEmpty() || item.optString("title").isEmpty()) continue;
            conversations.put(item);
        }
        try {
            JSONObject stored = new JSONObject().put("entry", active.optString("id")).put("conversations", conversations)
                .put("snooze", Boolean.TRUE.equals(call.getBoolean("snooze", false)));
            prefs(getContext()).edit().putString(KEY_STORED, stored.toString()).apply();
        } catch (JSONException e) {
            call.reject("Could not save shortcuts.");
            return;
        }
        refresh(getContext());
        call.resolve();
    }

    @PluginMethod
    public void take(PluginCall call) {
        JSONObject active = ServerConfigPlugin.activeServer(getContext());
        JSObject result = new JSObject();
        result.put("conversationId", JSONObject.NULL);
        result.put("action", JSONObject.NULL);
        if (pendingEntry != null && active != null && active.optString("id").equals(pendingEntry)) {
            if (pendingSnooze) result.put("action", "snooze");
            else result.put("conversationId", pendingConversation);
            pendingEntry = null;
            pendingConversation = null;
            pendingSnooze = false;
        }
        call.resolve(result);
    }

    /**
     * Before the bridge is built, so a shortcut for another entry brings the
     * activity up on that origin.
     */
    static void prepareLaunch(Activity activity) {
        take(activity, activity.getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String before = activeId(getContext());
        if (!take(getContext(), intent)) return;
        String after = activeId(getContext());
        if (after == null || !after.equals(before)) {
            // The bridge is bound to the old origin; rebuild it on the new one.
            getActivity().runOnUiThread(() -> getActivity().recreate());
        } else {
            // Retained: a page still loading gets it once it listens.
            notifyListeners("shortcutOpened", new JSObject(), true);
        }
    }

    /** Applies the shortcut's server change and stashes its conversation; false if it isn't one. */
    private static boolean take(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return false;
        String entry = intent.getStringExtra(EXTRA_ENTRY);
        String conversation = intent.getStringExtra(EXTRA_CONVERSATION);
        boolean servers = intent.getBooleanExtra(EXTRA_SERVERS, false);
        // Consumed: a recreate re-reads the intent and must not act again.
        intent.setAction(null);
        if (servers) {
            ServerConfigPlugin.clearActive(context);
            return true;
        }
        boolean snooze = intent.getBooleanExtra(EXTRA_SNOOZE, false);
        if (entry == null || (conversation == null && !snooze) || !hasServer(context, entry)) return false;
        pendingEntry = entry;
        pendingConversation = conversation;
        pendingSnooze = snooze;
        ServerConfigPlugin.activate(context, entry);
        return true;
    }

    /** Signed out of or removed: its conversations must not stay on the launcher. */
    static void forget(Context context, String entry) {
        JSONObject stored = stored(context);
        if (stored != null && entry.equals(stored.optString("entry"))) {
            prefs(context).edit().remove(KEY_STORED).apply();
        }
        refresh(context);
    }

    /** Also called when the server list changes, which can add or drop "Switch server". */
    static void refresh(Context context) {
        JSONArray servers = ServerConfigPlugin.storedServers(context);
        int count = servers.length();
        List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
        JSONObject stored = stored(context);
        String entry = stored == null ? null : stored.optString("entry");
        boolean current = entry != null && hasServer(context, entry);
        boolean snooze = current && stored.optBoolean("snooze");
        if (current) {
            JSONArray conversations = stored.optJSONArray("conversations");
            int limit = LIMIT - (snooze ? 1 : 0) - (count > 1 ? 1 : 0);
            for (int i = 0; conversations != null && i < conversations.length() && shortcuts.size() < limit; i++) {
                JSONObject c = conversations.optJSONObject(i);
                if (c == null) continue;
                String title = c.optString("title");
                String subtitle = c.optString("subtitle", "");
                Intent intent = intent(context)
                    .putExtra(EXTRA_ENTRY, entry)
                    .putExtra(EXTRA_CONVERSATION, c.optString("id"));
                shortcuts.add(new ShortcutInfoCompat.Builder(context, "conversation:" + c.optString("id"))
                    .setShortLabel(title)
                    .setLongLabel(subtitle.isEmpty() ? title : title + " · " + subtitle)
                    .setIcon(IconCompat.createWithResource(context, icon(c.optString("kind"))))
                    .setIntent(intent)
                    .setRank(i)
                    .build());
            }
        }
        if (snooze) {
            shortcuts.add(new ShortcutInfoCompat.Builder(context, "snooze")
                .setShortLabel(context.getString(R.string.shortcut_snooze))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_snooze))
                .setIntent(intent(context).putExtra(EXTRA_ENTRY, entry).putExtra(EXTRA_SNOOZE, true))
                .setRank(LIMIT - 1)
                .build());
        }
        if (count > 1) {
            shortcuts.add(new ShortcutInfoCompat.Builder(context, "servers")
                .setShortLabel(context.getString(R.string.shortcut_switch_server))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_servers))
                .setIntent(intent(context).putExtra(EXTRA_SERVERS, true))
                .setRank(LIMIT)
                .build());
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Rate-limited or rejected by the launcher; the next change retries.
        }
    }

    private static Intent intent(Context context) {
        return new Intent(context, ShortcutActivity.class).setAction(ACTION);
    }

    private static int icon(String kind) {
        if ("dm".equals(kind)) return R.drawable.ic_shortcut_dm;
        if ("group".equals(kind)) return R.drawable.ic_shortcut_group;
        return R.drawable.ic_shortcut_channel;
    }

    private static boolean hasServer(Context context, String id) {
        JSONArray servers = ServerConfigPlugin.storedServers(context);
        for (int i = 0; i < servers.length(); i++) {
            JSONObject entry = servers.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return true;
        }
        return false;
    }

    private static String activeId(Context context) {
        JSONObject active = ServerConfigPlugin.activeServer(context);
        return active == null ? null : active.optString("id");
    }

    private static JSONObject stored(Context context) {
        String raw = prefs(context).getString(KEY_STORED, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
