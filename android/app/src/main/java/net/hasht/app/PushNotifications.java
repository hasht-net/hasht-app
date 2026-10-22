package net.hasht.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns a decrypted push into a notification, or withdraws one.
 *
 * Notifications are keyed by account and payload tag. New backends provide
 * one tag per message; older backends replace the channel notification.
 */
final class PushNotifications {
    private PushNotifications() {}

    static final String CHANNEL_ID = "messages";
    static final String EXTRA_ORIGIN = "net.hasht.app.push.origin";
    static final String EXTRA_URL = "net.hasht.app.push.url";
    private static final String EXTRA_IDS = "net.hasht.app.push.ids";
    private static final int ID = 1;
    private static final String RECOVERY_TAG = "push-recovery";
    private static final String ORDER_PREFS = "net.hasht.app.push.order";
    private static final String ORDER_KEY = "state";
    private static PushOrder.State orderState;

    /** A decrypted payload: `{title, body, url, tag, icon, kind, ids}`. */
    static final class Message {
        final String origin;
        final String registrationId;
        final String title;
        final String body;
        /** A path on `origin`. */
        final String url;
        final String tag;
        /** `message`, `update` or `remove`; older backends send none. */
        final String kind;
        final List<String> ids;
        final long created;
        final long version;
        final long issued;

        Message(String origin, JSONObject json) { this(origin, null, json); }

        Message(String origin, String registrationId, JSONObject json) {
            this.origin = origin;
            this.registrationId = registrationId;
            this.title = json.optString("title", "Hasht");
            this.body = json.optString("body", "");
            this.url = json.optString("url", "/");
            this.tag = json.optString("tag", "");
            this.kind = json.optString("kind", "message");
            List<String> ids = new ArrayList<>();
            JSONArray raw = json.optJSONArray("ids");
            for (int i = 0; raw != null && i < raw.length(); i++) ids.add(raw.optString(i));
            this.ids = ids;
            JSONObject order = json.optJSONObject("native_order");
            this.created = order == null ? 0 : order.optLong("created", 0);
            this.version = order == null ? 0 : order.optLong("version", 0);
            this.issued = json.optLong("issued_at", 0);
        }

        String notificationTag() {
            return origin + " " + tag;
        }
    }

    /** Tries each instance's key; the one whose GCM tag verifies sent it. */
    static Message open(Context context, String enc) {
        byte[] body;
        try {
            body = PushStore.unb64(enc);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (PushStore.Registration reg : PushStore.load(context)) {
            try {
                byte[] plain = WebPushCrypto.decrypt(body, reg.key(), reg.publicKey, reg.auth);
                return new Message(reg.scope(), reg.id, new JSONObject(new String(plain, StandardCharsets.UTF_8)));
            } catch (Exception wrongKey) {
                // Next instance.
            }
        }
        return null;
    }

    /** State commit and notification effects share one lock, including UI clears. */
    static synchronized void receive(Context context, Message message) {
        boolean registered = false;
        for (PushStore.Registration reg : PushStore.load(context)) {
            if (reg.scope().equals(message.origin) && reg.id.equals(message.registrationId)) registered = true;
        }
        if (!registered) return;
        PushOrder.State state = state(context);
        long generation = state.generation;
        if (!state.acceptNotification(message.origin, message.tag, message.kind, message.ids,
                message.created, message.version, message.issued, System.currentTimeMillis())) {
            if (save(context)) {
                if (generation != state.generation) {
                    for (String origin : state.affectedOrigins()) {
                        remove(context, origin, null, Collections.emptyList());
                        showRecovery(context, origin);
                    }
                } else showRecovery(context, message.origin);
            }
            return;
        }
        if (!save(context)) return;
        if ("remove".equals(message.kind)) remove(context, message.origin, message.tag, message.ids);
        else show(context, message);
    }

    private static void show(Context context, Message message) {
        // Mirrors the web service worker: the page on screen already showed it.
        if (MainActivity.isVisible() && message.origin.equals(NativePushPlugin.activeAccountKey(context))) return;

        ensureChannel(context);
        Intent tap = new Intent(context, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ORIGIN, message.origin)
            .putExtra(EXTRA_URL, message.url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
            context, message.notificationTag().hashCode(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Bundle extras = new Bundle();
        extras.putLong("net.hasht.app.push.created", message.created);
        extras.putString("net.hasht.app.push.tag", message.tag);
        extras.putString(EXTRA_ORIGIN, message.origin);
        extras.putString(EXTRA_IDS, TextUtils.join(",", message.ids));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addExtras(extras);
        // An edit replaces the text without a second alert.
        if ("update".equals(message.kind)) builder.setOnlyAlertOnce(true);

        try {
            NotificationManagerCompat.from(context).notify(message.notificationTag(), ID, builder.build());
        } catch (SecurityException notificationsDenied) {
            // POST_NOTIFICATIONS revoked after registering; nothing to show.
        }
    }

    /**
     * Withdraws notifications from `origin`, narrowed to a channel `tag` and,
     * if `ids` is non-empty, to ones about any of those messages.
     */
    static synchronized void remove(Context context, String origin, String tag, List<String> ids) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            Bundle extras = shown.getNotification().extras;
            if (!origin.equals(extras.getString(EXTRA_ORIGIN))) continue;
            if (tag != null) {
                String expected = origin + " " + tag.split("/message:", 2)[0];
                String shownTag = shown.getTag();
                if (shownTag == null || !expected.equals(shownTag.split("/message:", 2)[0])) continue;
            }
            if (!ids.isEmpty()) {
                String shownIds = extras.getString(EXTRA_IDS, "");
                if (Collections.disjoint(Arrays.asList(shownIds.split(",")), ids)) continue;
            }
            manager.cancel(shown.getTag(), shown.getId());
        }
    }

    static synchronized void reconcileReads(Context context, String origin, org.json.JSONArray reads) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            Bundle extras = shown.getNotification().extras;
            if (!origin.equals(extras.getString(EXTRA_ORIGIN))) continue;
            long created = extras.getLong("net.hasht.app.push.created", 0);
            String tag = extras.getString("net.hasht.app.push.tag", "");
            if (created <= 0) continue;
            String channelTag = tag.split("/message:", 2)[0];
            for (int i = 0; i < reads.length(); i++) {
                org.json.JSONObject read = reads.optJSONObject(i);
                if (read != null && channelTag.equals(read.optString("tag")) && created <= read.optDouble("through", 0)) {
                    List<String> ids = Arrays.asList(extras.getString(EXTRA_IDS, "").split(","));
                    state(context).accept(origin, tag, "remove", ids, created, created,
                        System.currentTimeMillis(), System.currentTimeMillis());
                    if (save(context)) manager.cancel(shown.getTag(), shown.getId());
                    break;
                }
            }
        }
    }

    static synchronized void clearChannel(Context context, String origin, String tag) {
        state(context).clear(origin, tag, System.currentTimeMillis());
        if (!save(context)) throw new IllegalStateException("could not store notification read state");
        remove(context, origin, tag, Collections.emptyList());
    }

    static synchronized void forget(Context context, String origin) {
        state(context).forget(origin);
        save(context);
        remove(context, origin, null, Collections.emptyList());
    }

    static synchronized void overflow(Context context) {
        List<String> origins = new ArrayList<>();
        for (PushStore.Registration reg : PushStore.load(context)) origins.add(reg.scope());
        state(context).overflow(origins, System.currentTimeMillis());
        if (!save(context)) return;
        for (String origin : origins) {
            remove(context, origin, null, Collections.emptyList());
            showRecovery(context, origin);
        }
    }

    static synchronized long recoveryGeneration(Context context, String origin) {
        return state(context).recoveryGeneration(origin);
    }

    static synchronized void completeRecovery(Context context, String origin, long generation) {
        PushOrder.State state = state(context);
        if (!state.complete(origin, generation)) return;
        if (!save(context)) {
            state.recovery.put(origin, generation);
            return;
        }
        NotificationManagerCompat.from(context).cancel(origin + " " + RECOVERY_TAG, ID);
    }

    private static PushOrder.State state(Context context) {
        if (orderState != null) return orderState;
        String encoded = context.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(ORDER_KEY, null);
        try {
            orderState = encoded == null ? new PushOrder.State() : PushOrder.State.decode(PushStore.unb64(encoded));
        } catch (Exception corrupt) {
            orderState = new PushOrder.State();
            List<String> origins = new ArrayList<>();
            for (PushStore.Registration reg : PushStore.load(context)) origins.add(reg.scope());
            orderState.overflow(origins, System.currentTimeMillis());
        }
        return orderState;
    }

    private static boolean save(Context context) {
        try {
            return context.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit()
                .putString(ORDER_KEY, PushStore.b64(orderState.encode())).commit();
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static void showRecovery(Context context, String origin) {
        if (state(context).recoveryGeneration(origin) == 0) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", "Hasht").put("body", context.getString(R.string.notification_push_recovery))
                .put("url", "/").put("tag", RECOVERY_TAG).put("kind", "update");
        } catch (Exception impossible) { return; }
        show(context, new Message(origin, payload));
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH));
    }
}
