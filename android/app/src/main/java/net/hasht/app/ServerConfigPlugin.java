package net.hasht.app;

import android.content.SharedPreferences;
import android.net.Uri;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Points the webview at the instance the user configured.
 *
 * Capacitor's server.url is static config, so the instance a self-hoster types
 * in can only be applied natively. On launch we reload straight into the stored
 * instance; with nothing stored the bundled bootstrap picker is what the user
 * sees.
 */
@CapacitorPlugin(name = "ServerConfig")
public class ServerConfigPlugin extends Plugin {

    private static final String PREFS = "net.hasht.app";
    private static final String KEY_URL = "serverURL";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, 0);
    }

    @Override
    public void load() {
        String url = prefs().getString(KEY_URL, null);
        if (url != null) {
            loadInstance(url);
        }
    }

    @PluginMethod
    public void get(PluginCall call) {
        JSObject result = new JSObject();
        result.put("url", prefs().getString(KEY_URL, null));
        call.resolve(result);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String url = call.getString("url");
        if (url == null || Uri.parse(url).getScheme() == null) {
            call.reject("a valid absolute URL is required");
            return;
        }
        prefs().edit().putString(KEY_URL, url).apply();
        loadInstance(url);
        call.resolve();
    }

    /**
     * Back to the bundled picker — sign-out, or an instance that no longer
     * answers and would otherwise leave the app pinned to a dead origin.
     */
    @PluginMethod
    public void reset(PluginCall call) {
        prefs().edit().remove(KEY_URL).apply();
        final String local = getBridge().getLocalUrl();
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(local));
        call.resolve();
    }

    private void loadInstance(final String url) {
        getActivity().runOnUiThread(() -> getBridge().getWebView().loadUrl(url));
    }
}
