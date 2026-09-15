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
        setActiveId(null);
        final String local = getBridge().getLocalUrl();
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(local));
        call.resolve();
    }

    private void loadInstance(final String url) {
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(url));
    }
}
