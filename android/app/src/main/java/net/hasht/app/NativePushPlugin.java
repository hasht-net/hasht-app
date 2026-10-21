package net.hasht.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Native push through the Hasht push relay (`Hasht-net/hasht-push-relay`).
 *
 * The page calls `register`; this gets an FCM token, creates a relay
 * registration and a P-256 keypair for the active instance, and returns the
 * triple the instance's `/api/push/subscribe` takes. The instance then sends
 * ordinary Web Push to the relay, the relay hands ciphertext to FCM, and
 * `HashtMessagingService` decrypts it on the device.
 */
@CapacitorPlugin(
    name = "NativePush",
    permissions = @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
)
public class NativePushPlugin extends Plugin {
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** A tapped notification's path, waiting for its instance's page to load. */
    private static String pendingTapUrl;

    @Override
    public void load() {
        deliverPendingTap();
    }

    // --- From the page

    @PluginMethod
    public void register(PluginCall call) {
        String relay = call.getString("relayUrl");
        if (relay == null || !relay.startsWith("https://")) {
            call.reject("relayUrl must be an https URL");
            return;
        }
        boolean allowed = Build.VERSION.SDK_INT < 33
            ? NotificationManagerCompat.from(getContext()).areNotificationsEnabled()
            : getPermissionState("notifications") == PermissionState.GRANTED;
        if (!allowed) {
            // `prompt: false` is the page re-checking on load, which must
            // never put a permission dialog in front of the user.
            if (!call.getBoolean("prompt", true) || Build.VERSION.SDK_INT < 33) {
                call.reject("Notifications are turned off for Hasht.", "denied");
                return;
            }
            requestPermissionForAlias("notifications", call, "notificationsPermission");
            return;
        }
        registerWithRelay(call);
    }

    @PermissionCallback
    private void notificationsPermission(PluginCall call) {
        if (getPermissionState("notifications") != PermissionState.GRANTED) {
            call.reject("Notifications are turned off for Hasht.", "denied");
            return;
        }
        registerWithRelay(call);
    }

    private void registerWithRelay(PluginCall call) {
        JSONObject active = ServerConfigPlugin.activeServer(getContext());
        String backendOrigin = active == null ? null : ServerConfigPlugin.originKey(Uri.parse(active.optString("url")));
        String origin = activeAccountKey(getContext());
        if (origin == null) {
            call.reject("NativePush.register must be called from an instance's page");
            return;
        }
        if (FirebaseApp.getApps(getContext()).isEmpty()) {
            // A build without google-services.json; see README, "Push".
            call.reject("This build of Hasht can't receive notifications.", "unavailable");
            return;
        }
        String relay = call.getString("relayUrl");
        Context context = getContext();
        executor.execute(() -> {
            try {
                String token = Tasks.await(FirebaseMessaging.getInstance().getToken());
                PushStore.setToken(context, token);
                PushStore.Registration reg = PushRelay.register(context, origin, relay, token, backendOrigin);
                JSObject result = new JSObject();
                result.put("endpoint", reg.endpoint);
                result.put("p256dh", PushStore.b64(reg.publicKey));
                result.put("auth", PushStore.b64(reg.auth));
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Couldn't turn on notifications: " + e.getMessage(), "relay", e);
            }
        });
    }

    @PluginMethod
    public void unregister(PluginCall call) {
        String origin = activeAccountKey(getContext());
        try {
            if (origin != null) unregister(getContext(), origin);
            call.resolve();
        } catch (IllegalStateException failure) {
            call.reject(failure.getMessage(), "storage", failure);
        }
    }

    /** The page read the channel; withdraw what's still in the shade. */
    @PluginMethod
    public void clearChannel(PluginCall call) {
        String tag = call.getString("tag");
        if (tag == null) {
            call.reject("tag is required");
            return;
        }
        String origin = activeAccountKey(getContext());
        try {
            if (origin != null) PushNotifications.clearChannel(getContext(), origin, tag);
            call.resolve();
        } catch (IllegalStateException failure) {
            call.reject(failure.getMessage(), "storage", failure);
        }
    }

    @PluginMethod
    public void recoveryNeeded(PluginCall call) {
        String origin = activeAccountKey(getContext());
        recoveryAccountKey = origin;
        long generation = origin == null ? 0 : PushNotifications.recoveryGeneration(getContext(), origin);
        JSObject result = new JSObject();
        result.put("needed", generation != 0);
        result.put("generation", generation);
        result.put("origin", activeBackendOrigin(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void completeRecovery(PluginCall call) {
        String expected = call.getString("origin");
        String origin = activeAccountKey(getContext());
        Long generation = call.getLong("generation");
        if (origin != null && origin.equals(recoveryAccountKey)
            && activeBackendOrigin(getContext()).equals(expected) && generation != null) {
            PushNotifications.completeRecovery(getContext(), origin, generation);
        }
        call.resolve();
    }

    // --- From the shell

    private String recoveryAccountKey;

    /** Relay row, keys and delivered notifications for `origin`. Also sign-out. */
    static void unregister(Context context, String origin) {
        java.util.List<PushStore.Registration> gone;
        synchronized (PushNotifications.class) {
            gone = PushRelay.dropLocal(context, origin);
            PushNotifications.forget(context, origin);
        }
        executor.execute(() -> PushRelay.deleteRegistrations(gone));
    }

    static String activeBackendOrigin(Context context) {
        JSONObject active = ServerConfigPlugin.activeServer(context);
        return active == null ? null : ServerConfigPlugin.originKey(Uri.parse(active.optString("url")));
    }

    static String activeAccountKey(Context context) {
        JSONObject active = ServerConfigPlugin.activeServer(context);
        return active == null ? null : ServerConfigPlugin.pushKey(active);
    }

    /**
     * Before the bridge is built: a tap on another instance's notification
     * switches to that instance, so the activity comes up on the right origin.
     */
    static void prepareLaunch(Activity activity) {
        takeTap(activity, activity.getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (takeTap(getContext(), intent)) {
            // The bridge is bound to the old origin; rebuild it on the new one.
            getActivity().runOnUiThread(() -> getActivity().recreate());
        } else {
            deliverPendingTap();
        }
    }

    /** Stashes the tap's path; true if it also switched the active server. */
    private static boolean takeTap(Context context, Intent intent) {
        if (intent == null) return false;
        String origin = intent.getStringExtra(PushNotifications.EXTRA_ORIGIN);
        String url = intent.getStringExtra(PushNotifications.EXTRA_URL);
        if (origin == null || url == null) return false;
        // Consumed: a recreate re-reads the intent and must not tap again.
        intent.removeExtra(PushNotifications.EXTRA_ORIGIN);
        intent.removeExtra(PushNotifications.EXTRA_URL);

        if (origin.equals(activeAccountKey(context))) {
            pendingTapUrl = url;
            return false;
        }
        String id = ServerConfigPlugin.serverIdForPushKey(context, origin);
        if (id == null) return false; // The server was removed since.
        ServerConfigPlugin.activate(context, id);
        pendingTapUrl = url;
        return true;
    }

    /** Retained, so it reaches the page once it has loaded and listens. */
    private void deliverPendingTap() {
        if (pendingTapUrl == null) return;
        JSObject data = new JSObject();
        data.put("url", pendingTapUrl);
        pendingTapUrl = null;
        notifyListeners("notificationTapped", data, true);
    }
}
