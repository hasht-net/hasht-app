package net.hasht.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import org.json.JSONObject;

/**
 * Talks to the Hasht push relay. Blocking: call off the main thread.
 */
final class PushRelay {
    private PushRelay() {}

    private static final Object storageLock = new Object();
    private static final java.util.Map<String, Long> generations = new java.util.HashMap<>();

    private static final class Response {
        final int status;
        final JSONObject body;

        Response(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * The registration for `origin`, created if needed. An existing one is
     * refreshed with the current token; if the relay forgot it (pruned, or
     * its token died) a new one replaces it and the page re-subscribes with
     * the new endpoint.
     */
    static synchronized PushStore.Registration register(Context context, String origin, String relay, String token, String backendOrigin)
        throws Exception {
        String base = relay.endsWith("/") ? relay.substring(0, relay.length() - 1) : relay;
        PushStore.Registration existing = null;
        long generation;
        synchronized (storageLock) {
            generation = generations.getOrDefault(origin, 0L) + 1;
            generations.put(origin, generation);
            for (PushStore.Registration reg : PushStore.load(context)) {
                if (reg.scope().equals(origin)) { existing = reg; break; }
            }
        }
        if (existing != null) {
            if (existing.relayUrl.equals(base)) {
                Response r = request("PUT", base + "/v1/register/" + existing.id, body(context, token));
                if (r.status == 204) {
                    synchronized (storageLock) {
                        requireCurrent(origin, generation);
                        return existing;
                    }
                }
                // Only a missing row justifies replacing its endpoint and keys.
                if (r.status != 404) throw new IllegalStateException("relay answered " + r.status);
            } else {
                quietly("DELETE", existing.relayUrl + "/v1/register/" + existing.id);
            }
        }
        synchronized (storageLock) { requireCurrent(origin, generation); }
        Response r = request("POST", base + "/v1/register", body(context, token));
        if (r.status != 201 || r.body == null) throw new IllegalStateException("relay answered " + r.status);

        KeyPair keys = WebPushCrypto.generate();
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        PushStore.Registration reg = new PushStore.Registration(
            backendOrigin, base, r.body.getString("id"), r.body.getString("endpoint"),
            keys.getPrivate().getEncoded(), WebPushCrypto.encodePublic((ECPublicKey) keys.getPublic()), auth);
        reg.accountKey = origin.equals(backendOrigin) ? null : origin;
        boolean cancelled;
        synchronized (storageLock) {
            cancelled = generations.getOrDefault(origin, 0L) != generation;
            if (!cancelled) {
                // Other instances may have registered while the network was busy.
                List<PushStore.Registration> regs = PushStore.load(context);
                regs.removeIf(saved -> saved.scope().equals(origin));
                regs.add(reg);
                PushStore.save(context, regs);
            }
        }
        if (cancelled) {
            quietly("DELETE", base + "/v1/register/" + reg.id);
            throw new IllegalStateException("push registration was cancelled");
        }
        return reg;
    }

    private static void requireCurrent(String origin, long generation) {
        if (generations.getOrDefault(origin, 0L) != generation) {
            throw new IllegalStateException("push registration was cancelled");
        }
    }

    /** Revoke decryption keys synchronously; relay deletion can wait for the network. */
    static List<PushStore.Registration> dropLocal(Context context, String origin) {
        synchronized (storageLock) {
            generations.put(origin, generations.getOrDefault(origin, 0L) + 1);
            List<PushStore.Registration> regs = PushStore.load(context);
            List<PushStore.Registration> gone = new java.util.ArrayList<>();
            for (int i = regs.size() - 1; i >= 0; i--) {
                if (regs.get(i).scope().equals(origin)) gone.add(regs.remove(i));
            }
            PushStore.save(context, regs);
            return gone;
        }
    }

    static void deleteRegistrations(List<PushStore.Registration> registrations) {
        for (PushStore.Registration reg : registrations) {
            quietly("DELETE", reg.relayUrl + "/v1/register/" + reg.id);
        }
    }

    /** Points every registration at a rotated FCM token. */
    static synchronized void refreshToken(Context context, String token) {
        for (PushStore.Registration reg : PushStore.load(context)) {
            try {
                request("PUT", reg.relayUrl + "/v1/register/" + reg.id, body(context, token));
            } catch (Exception e) {
                android.util.Log.w("NativePush", "token refresh failed", e);
            }
        }
    }

    private static JSONObject body(Context context, String token) throws Exception {
        return new JSONObject()
            .put("platform", "android")
            .put("token", token)
            // Only APNs has environments; the relay ignores this for FCM.
            .put("environment", "production")
            .put("app_version", appVersion(context));
    }

    private static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo info =
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + "+" + code;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static void quietly(String method, String url) {
        try {
            request(method, url, null);
        } catch (Exception e) {
            // Best effort: the relay prunes what it never delivers to.
        }
    }

    private static Response request(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            JSONObject json = null;
            if (in != null) {
                try (InputStream stream = in) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = stream.read(chunk)) != -1) buffer.write(chunk, 0, n);
                    String text = buffer.toString("UTF-8");
                    if (text.startsWith("{")) json = new JSONObject(text);
                }
            }
            return new Response(status, json);
        } finally {
            conn.disconnect();
        }
    }
}
