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
 * Notifications are keyed by tag `<origin> <payload tag>`, so a newer message
 * in a channel replaces the older one in place, and two instances never
 * collide.
 */
final class PushNotifications {
    private PushNotifications() {}

    static final String CHANNEL_ID = "messages";
    static final String EXTRA_ORIGIN = "net.hasht.app.push.origin";
    static final String EXTRA_URL = "net.hasht.app.push.url";
    private static final String EXTRA_IDS = "net.hasht.app.push.ids";
    private static final int ID = 1;

    /** A decrypted payload: `{title, body, url, tag, icon, kind, ids}`. */
    static final class Message {
        final String origin;
        final String title;
        final String body;
        /** A path on `origin`. */
        final String url;
        final String tag;
        /** `message`, `update` or `remove`; older backends send none. */
        final String kind;
        final List<String> ids;

        Message(String origin, JSONObject json) {
            this.origin = origin;
            this.title = json.optString("title", "Hasht");
            this.body = json.optString("body", "");
            this.url = json.optString("url", "/");
            this.tag = json.optString("tag", "");
            this.kind = json.optString("kind", "message");
            List<String> ids = new ArrayList<>();
            JSONArray raw = json.optJSONArray("ids");
            for (int i = 0; raw != null && i < raw.length(); i++) ids.add(raw.optString(i));
            this.ids = ids;
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
                return new Message(reg.origin, new JSONObject(new String(plain, StandardCharsets.UTF_8)));
            } catch (Exception wrongKey) {
                // Next instance.
            }
        }
        return null;
    }

    static void show(Context context, Message message) {
        // Mirrors the web service worker: the page on screen already showed it.
        if (MainActivity.isVisible() && message.origin.equals(NativePushPlugin.activeOrigin(context))) return;

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
    static void remove(Context context, String origin, String tag, List<String> ids) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            Bundle extras = shown.getNotification().extras;
            if (!origin.equals(extras.getString(EXTRA_ORIGIN))) continue;
            if (tag != null && !(origin + " " + tag).equals(shown.getTag())) continue;
            if (!ids.isEmpty()) {
                String shownIds = extras.getString(EXTRA_IDS, "");
                if (Collections.disjoint(Arrays.asList(shownIds.split(",")), ids)) continue;
            }
            manager.cancel(shown.getTag(), shown.getId());
        }
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
