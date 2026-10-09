package net.hasht.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Window;

import com.getcapacitor.CapConfig;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * The servers the user has added, and which one the webview is pointed at.
 *
 * Capacitor's server.url is static config, so the instance a self-hoster types
 * in has to be injected into that config before the bridge starts. MainActivity
 * asks runtimeConfig() for a CapConfig carrying the active server, so Capacitor
 * treats it as the app origin: it intercepts that host and injects the bridge,
 * which a plain webView.loadUrl() to a remote origin never gets. With no active
 * server the bundled wizard is what the user sees.
 *
 * Switching servers therefore recreates the activity rather than navigating -
 * the config is read once, at bridge construction.
 *
 * Mirrors hasht-desktop's serverStore.ts. Holds only which servers the user
 * added — never credentials, which stay in each origin's own cookie jar.
 */
@CapacitorPlugin(name = "ServerConfig")
public class ServerConfigPlugin extends Plugin {

    private static final String PREFS = "net.hasht.app";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE = "activeServerId";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    private SharedPreferences prefs() {
        return prefs(getContext());
    }

    private JSONArray servers() {
        try {
            return new JSONArray(prefs().getString(KEY_SERVERS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void saveServers(JSONArray servers) {
        prefs().edit().putString(KEY_SERVERS, servers.toString()).apply();
    }

    private String activeId() {
        return prefs().getString(KEY_ACTIVE, null);
    }

    private void setActiveId(String id) {
        SharedPreferences.Editor editor = prefs().edit();
        if (id == null) {
            editor.remove(KEY_ACTIVE);
        } else {
            editor.putString(KEY_ACTIVE, id);
        }
        editor.apply();
    }

    private JSONObject find(String id) throws JSONException {
        JSONArray all = servers();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.getJSONObject(i);
            if (entry.getString("id").equals(id)) return entry;
        }
        return null;
    }

    /**
     * The Capacitor config the activity should start the bridge with: the
     * bundled config with server.url pointed at the active instance, or null
     * when there is none and the bundled wizard should load instead.
     *
     * Written to and reloaded from the app's file-space because CapConfig can
     * only be built from a Builder (which would silently drop every key we did
     * not re-list) or from a file. Round-tripping the real capacitor.config.json
     * keeps the two in sync on their own.
     */
    static CapConfig runtimeConfig(Context context) {
        String url = activeServerUrl(context);
        if (url == null) return null;
        try {
            JSONObject config = new JSONObject(readAsset(context, "capacitor.config.json"));
            JSONObject server = config.optJSONObject("server");
            if (server == null) server = new JSONObject();
            server.put("url", url);
            config.put("server", server);

            File dir = context.getFilesDir();
            try (Writer writer = new OutputStreamWriter(
                     new FileOutputStream(new File(dir, "capacitor.config.json")),
                     StandardCharsets.UTF_8)) {
                writer.write(config.toString());
            }
            return CapConfig.loadFromFile(context, dir.getAbsolutePath());
        } catch (Exception e) {
            // Fall back to the wizard rather than refusing to start.
            return null;
        }
    }

    private static String readAsset(Context context, String name) throws java.io.IOException {
        try (InputStream in = context.getAssets().open(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            return out.toString();
        }
    }

    private static String activeServerUrl(Context context) {
        JSONObject active = activeServer(context);
        return active == null ? null : active.optString("url", null);
    }

    // --- For NativeAuth. Static because switching servers recreates the
    // activity, and every plugin instance with it.

    static JSONObject activeServer(Context context) {
        SharedPreferences prefs = prefs(context);
        String id = prefs.getString(KEY_ACTIVE, null);
        if (id == null) return null;
        try {
            JSONArray all = new JSONArray(prefs.getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (id.equals(entry.getString("id"))) return entry;
            }
        } catch (JSONException e) {
            // Corrupt store: the user picks again.
        }
        return null;
    }

    /** Compared by origin rather than string, so a trailing slash still matches. */
    static String serverIdForOrigin(Context context, Uri origin) {
        try {
            JSONArray all = new JSONArray(prefs(context).getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (originKey(Uri.parse(entry.getString("url"))).equals(originKey(origin))) {
                    return entry.getString("id");
                }
            }
        } catch (JSONException e) {
            // Corrupt store: treat as not added.
        }
        return null;
    }

    /** Adds and activates; the caller recreates the activity to load it. */
    static String addAndActivate(Context context, String url, String name) throws JSONException {
        SharedPreferences prefs = prefs(context);
        JSONArray all = new JSONArray(prefs.getString(KEY_SERVERS, "[]"));
        JSONObject entry = new JSONObject();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("url", url);
        entry.put("name", name);
        all.put(entry);
        prefs.edit()
            .putString(KEY_SERVERS, all.toString())
            .putString(KEY_ACTIVE, entry.getString("id"))
            .apply();
        return entry.getString("id");
    }

    static void activate(Context context, String id) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply();
    }

    /** Best-effort, off the main thread: null when the name can't be read. */
    static String fetchName(String base) {
        try {
            String appName = new JSONObject(get(base + "/api/instance-settings")).optString("app_name");
            return appName.isEmpty() ? null : appName;
        } catch (Exception e) {
            return null;
        }
    }

    static String originKey(Uri url) {
        String port = url.getPort() == -1 ? "" : ":" + url.getPort();
        return (url.getScheme() + "://" + url.getHost() + port).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Confirms an origin is a Hasht instance and reports its name.
     *
     * Native rather than a fetch from the wizard: that page is served from a
     * local scheme, and no instance will have it in CORS_ALLOWED_ORIGINS.
     */
    @PluginMethod
    public void probe(PluginCall call) {
        final String base = call.getString("url");
        if (base == null) {
            call.reject("That doesn't look like a valid address.");
            return;
        }
        new Thread(() -> {
            try {
                String body = get(base + "/api/health");
                if (!"ok".equals(new JSONObject(body).optString("status"))) {
                    call.reject("That address doesn't look like a Hasht server.");
                    return;
                }
            } catch (Exception e) {
                call.reject("Couldn't reach that server: " + e.getMessage());
                return;
            }

            // Best-effort: a reachable instance with an unreadable name is
            // still fine to connect to.
            String name = fetchName(base);

            JSObject result = new JSObject();
            result.put("name", name != null ? name : Uri.parse(base).getHost());
            call.resolve(result);
        }).start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        try {
            if (connection.getResponseCode() != 200) {
                throw new Exception("server returned " + connection.getResponseCode());
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The frontend signed the user out. Drops session-scoped native state and
     * deliberately does not navigate — signing out leaves you on the same
     * instance's login screen, as it does on the web.
     *
     * Ends this instance's push registration too, so a page that forgets to
     * call NativePush.unregister doesn't leave the next user's phone receiving
     * the last one's messages.
     */
    @PluginMethod
    public void signOut(PluginCall call) {
        String origin = NativePushPlugin.activeOrigin(getContext());
        try {
            if (origin != null) NativePushPlugin.unregister(getContext(), origin);
            call.resolve();
        } catch (IllegalStateException failure) {
            call.reject(failure.getMessage(), "storage", failure);
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        JSObject result = new JSObject();
        result.put("servers", servers());
        result.put("activeId", activeId());
        call.resolve(result);
    }

    @PluginMethod
    public void add(PluginCall call) {
        String url = call.getString("url");
        Uri parsed = url == null ? null : Uri.parse(url);
        if (parsed == null || parsed.getScheme() == null || parsed.getHost() == null) {
            call.reject("a valid absolute URL is required");
            return;
        }

        String name = call.getString("name");
        if (name == null || name.trim().isEmpty()) name = parsed.getHost();

        try {
            JSONObject entry = new JSONObject();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("url", url);
            entry.put("name", name.trim());

            JSONArray all = servers();
            all.put(entry);
            saveServers(all);
            setActiveId(entry.getString("id"));
            restart();

            JSObject result = new JSObject();
            result.put("server", JSObject.fromJSONObject(entry));
            call.resolve(result);
        } catch (JSONException e) {
            call.reject("could not store that server");
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        try {
            String id = call.getString("id");
            JSONObject entry = id == null ? null : find(id);
            if (entry == null) {
                call.reject("unknown server");
                return;
            }
            setActiveId(id);
            restart();
            call.resolve();
        } catch (JSONException e) {
            call.reject("unknown server");
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String id = call.getString("id");
        if (id == null) {
            call.reject("id is required");
            return;
        }
        try {
            JSONArray all = servers();
            JSONArray kept = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (!entry.getString("id").equals(id)) kept.put(entry);
            }
            saveServers(kept);
            if (id.equals(activeId())) setActiveId(null);
            call.resolve();
        } catch (JSONException e) {
            call.reject("could not remove that server");
        }
    }

    @PluginMethod
    public void rename(PluginCall call) {
        String id = call.getString("id");
        String name = call.getString("name");
        if (id == null || name == null || name.trim().isEmpty()) {
            call.reject("id and a non-empty name are required");
            return;
        }
        try {
            JSONArray all = servers();
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (entry.getString("id").equals(id)) entry.put("name", name.trim());
            }
            saveServers(all);
            call.resolve();
        } catch (JSONException e) {
            call.reject("could not rename that server");
        }
    }

    /**
     * Paints the chrome the webview does not cover to match the page.
     *
     * The DayNight resources only get this right while the user is on "match
     * device"; pinning a theme is a page-level choice the system knows nothing
     * about. The page passes its own resolved color so the shade is defined in
     * exactly one place.
     */
    @PluginMethod
    public void setTheme(PluginCall call) {
        final boolean dark = "dark".equals(call.getString("theme"));
        final int color;
        try {
            color = Color.parseColor(call.getString("color", dark ? "#131417" : "#ffffff"));
        } catch (IllegalArgumentException e) {
            call.reject("not a color");
            return;
        }
        getActivity().runOnUiThread(() -> {
            Window window = getActivity().getWindow();
            window.setBackgroundDrawable(new ColorDrawable(color));
            getBridge().getWebView().setBackgroundColor(color);
            WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
            bars.setAppearanceLightStatusBars(!dark);
            bars.setAppearanceLightNavigationBars(!dark);
        });
        call.resolve();
    }

    /**
     * Back to the wizard without forgetting anything — sign-out, or an instance
     * that no longer answers and would otherwise leave the app pinned to a dead
     * origin.
     */
    @PluginMethod
    public void reset(PluginCall call) {
        returnToWizard();
        call.resolve();
    }

    /**
     * System-back equivalent of iOS's escape affordance: backing out of an
     * instance returns to the wizard rather than leaving the app, and forgets
     * the active server so the next launch does not reload it. Returns false
     * when there is no instance to back out of, so back behaves normally.
     */
    boolean returnToWizardIfRemote() {
        if (activeId() == null) return false;
        returnToWizard();
        return true;
    }

    private void returnToWizard() {
        setActiveId(null);
        restart();
    }

    /** Rebuilds the bridge against the config the new active server implies. */
    private void restart() {
        getActivity().runOnUiThread(() -> getActivity().recreate());
    }
}
