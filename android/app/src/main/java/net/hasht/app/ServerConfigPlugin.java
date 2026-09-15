package net.hasht.app;

import android.content.SharedPreferences;
import android.net.Uri;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * The servers the user has added, and which one the webview is pointed at.
 *
 * Capacitor's server.url is static config, so the instance a self-hoster types
 * in can only be applied natively. On launch we reload straight into the active
 * server; with none set the bundled wizard is what the user sees.
 *
 * Mirrors hasht-desktop's serverStore.ts. Holds only which servers the user
 * added — never credentials, which stay in each origin's own cookie jar.
 */
@CapacitorPlugin(name = "ServerConfig")
public class ServerConfigPlugin extends Plugin {

    private static final String PREFS = "net.hasht.app";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE = "activeServerId";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, 0);
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

    @Override
    public void load() {
        try {
            JSONObject active = activeId() == null ? null : find(activeId());
            if (active != null) loadInstance(active.getString("url"));
        } catch (JSONException e) {
            // A corrupt store just means the user picks again.
        }
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
            String name = Uri.parse(base).getHost();
            try {
                String settings = get(base + "/api/instance-settings");
                String appName = new JSONObject(settings).optString("app_name");
                if (!appName.isEmpty()) name = appName;
            } catch (Exception ignored) {
            }

            JSObject result = new JSObject();
            result.put("name", name);
            call.resolve(result);
        }).start();
    }

    private String get(String url) throws Exception {
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
            loadInstance(url);

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
            loadInstance(entry.getString("url"));
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
        final String local = getBridge().getLocalUrl();
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(local));
    }

    private void loadInstance(final String url) {
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(url));
    }
}
