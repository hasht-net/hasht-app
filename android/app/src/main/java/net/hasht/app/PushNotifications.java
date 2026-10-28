package net.hasht.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static PushOrderStore orderStore;

    /** A decrypted payload: `{title, body, url, tag, icon, image, kind, ids}`. */
    static final class Message {
        final String origin;
        final String registrationId;
        /** The instance itself; `origin` may be an account key. */
        final String backend;
        final String title;
        final String body;
        /** A path on `origin`. */
        final String url;
        final String tag;
        /** A signed attachment path on `backend`, shown beside the text. */
        final String image;
        /** `image`, fetched before `receive` so the download runs outside its lock. */
        Bitmap picture;
        /** Message, quiet catch-up, edit, exact removal, or channel read boundary. */
        final String kind;
        final List<String> ids;
        final long created;
        final long version;
        final long issued;
        final long readThrough;

        Message(String origin, JSONObject json) { this(origin, null, origin, json); }

        Message(String origin, String registrationId, String backend, JSONObject json) {
            this.origin = origin;
            this.registrationId = registrationId;
            this.backend = backend;
            this.title = json.optString("title", "Hasht");
            this.body = json.optString("body", "");
            this.url = json.optString("url", "/");
            this.tag = json.optString("tag", "");
            this.image = json.optString("image", "");
            this.kind = json.optString("kind", "message");
            List<String> ids = new ArrayList<>();
            JSONArray raw = json.optJSONArray("ids");
            for (int i = 0; raw != null && i < raw.length(); i++) ids.add(raw.optString(i));
            this.ids = ids;
            JSONObject order = json.optJSONObject("native_order");
            this.created = order == null ? 0 : order.optLong("created", 0);
            this.version = order == null ? 0 : order.optLong("version", 0);
            this.issued = json.optLong("issued_at", 0);
            this.readThrough = json.optLong("read_through", 0);
        }

        String notificationTag() {
            return origin + " " + tag;
        }

        String imageUrl() { return PushNotifications.imageUrl(backend, image); }
    }

    /** Only a path on the sending instance, never another host on a payload's say-so. */
    static String imageUrl(String backend, String image) {
        if (image == null || !image.startsWith("/") || image.startsWith("//")) return null;
        return backend + image;
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
                return new Message(reg.scope(), reg.id, reg.origin, new JSONObject(new String(plain, StandardCharsets.UTF_8)));
            } catch (Exception wrongKey) {
                // Next instance.
            }
        }
        return null;
    }

    private static final int PICTURE_MAX_BYTES = 10 * 1024 * 1024;
    private static final int PICTURE_MAX_SIDE = 1024;

    /**
     * Downloads the message's image, or null. The URL is signed, so no
     * session is needed; short timeouts keep FCM's processing window.
     */
    static Bitmap picture(Message message) {
        String raw = message.imageUrl();
        if (raw == null) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(raw).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > PICTURE_MAX_BYTES) return null;
                }
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int sample = 1;
            while (Math.max(options.outWidth, options.outHeight) / (sample * 2) >= PICTURE_MAX_SIDE) sample *= 2;
            options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** State commit and notification effects share one lock, including UI clears. */
    static synchronized void receive(Context context, Message message) {
        boolean registered = false;
        for (PushStore.Registration reg : PushStore.load(context)) {
            if (reg.scope().equals(message.origin) && reg.id.equals(message.registrationId)) registered = true;
        }
        if (!registered) return;
        if (!java.util.Arrays.asList("message", "update", "remove", "read", "catch_up").contains(message.kind)) return;
        if ("read".equals(message.kind)) {
            reconcileRead(context, message.origin, message.tag, message.readThrough);
            return;
        }
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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "catch_up".equals(message.kind) ? "message_catch_up" : CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority("catch_up".equals(message.kind) ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_HIGH)
            .setGroup(message.origin + " " + message.tag.split("/message:", 2)[0])
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addExtras(extras);
        if (message.picture != null) {
            builder.setLargeIcon(message.picture)
                .setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(message.picture)
                    .bigLargeIcon((Bitmap) null));
        }
        // An edit replaces the text without a second alert.
        if ("update".equals(message.kind)) builder.setOnlyAlertOnce(true);
        if ("catch_up".equals(message.kind)) builder.setSilent(true);

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
        java.util.Map<String, Long> markers = new java.util.HashMap<>();
        for (int i = 0; i < reads.length(); i++) {
            JSONObject read = reads.optJSONObject(i);
            if (read == null) continue;
            String tag = read.optString("tag", ""); long through = read.optLong("through", 0);
            if (!tag.startsWith("channel:") || tag.length() > 256 || through <= 0) continue;
            markers.merge(tag, through, Math::max);
            state(context).read(origin, tag, through);
        }
        state(context).compactReads();
        if (!save(context)) throw new IllegalStateException("could not store notification read state");
        cancelRead(context, origin, markers);
    }

    private static boolean reconcileRead(Context context, String origin, String tag, long through) {
        if (!tag.startsWith("channel:") || tag.contains("/message:") || through <= 0) return false;
        state(context).read(origin, tag, through);
        state(context).compactReads();
        if (!save(context)) return false;
        cancelRead(context, origin, Collections.singletonMap(tag, through));
        return true;
    }

    private static void cancelRead(Context context, String origin, java.util.Map<String, Long> markers) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            Bundle extras = shown.getNotification().extras;
            if (!origin.equals(extras.getString(EXTRA_ORIGIN))) continue;
            long created = extras.getLong("net.hasht.app.push.created", 0);
            String tag = extras.getString("net.hasht.app.push.tag", "").split("/message:", 2)[0];
            if (created > 0 && created <= markers.getOrDefault(tag, 0L)) manager.cancel(shown.getTag(), shown.getId());
        }
    }

    static synchronized void clearChannel(Context context, String origin, String tag, long through) {
        if (through > 0) {
            if (!reconcileRead(context, origin, tag, through)) throw new IllegalStateException("could not store notification read state");
            return;
        }
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
            state.metadataDirty = true;
            throw new IllegalStateException("could not store notification recovery state");
        }
        NotificationManagerCompat.from(context).cancel(origin + " " + RECOVERY_TAG, ID);
    }

    private static PushOrder.State state(Context context) {
        if (orderState != null) return orderState;
        orderStore = new PushOrderStore(context.getApplicationContext());
        try {
            if (orderStore.initialized()) orderState = orderStore.load();
            else {
                String encoded = context.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(ORDER_KEY, null);
                orderState = encoded == null ? new PushOrder.State() : PushOrder.State.decode(PushStore.unb64(encoded));
                // Legacy device-clock cutoffs are not authoritative server read boundaries.
                for (PushOrder.Entry entry : orderState.channels.values()) entry.cleared = 0;
                orderState.dirty.addAll(orderState.channels.keySet());
                orderStore.save(orderState);
                context.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit().remove(ORDER_KEY).apply();
            }
        } catch (Exception failure) {
            orderState = null;
            throw new IllegalStateException("could not load notification read state", failure);
        }
        return orderState;
    }

    private static boolean save(Context context) {
        try { orderStore.save(orderState); return true; }
        catch (Exception unavailable) {
            // Never continue with memory newer than durable state after a failed transaction.
            orderState = null;
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
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel("message_catch_up",
            "Messages received while away", NotificationManager.IMPORTANCE_LOW));
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH));
    }
}
