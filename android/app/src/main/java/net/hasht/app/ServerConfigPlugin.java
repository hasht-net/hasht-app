package net.hasht.app;

import android.app.AlertDialog;
import android.app.Activity;
import android.provider.DocumentsContract;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.annotation.ActivityCallback;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import android.content.Context;
import android.content.Intent;
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
import java.util.ArrayList;
import java.util.List;
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
 * added. Sign-in storage and push registrations are scoped to each entry.
 */
@CapacitorPlugin(name = "ServerConfig")
public class ServerConfigPlugin extends Plugin {

    private static final String PREFS = "net.hasht.app";
    private static final String KEY_REMOVED = "removedAccounts";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE = "activeServerId";
    /** Where a link goes when several entries share its origin. Links only. */
    private static final String KEY_DEFAULT = "defaultServerId";

    /** A link to load once the activity is back up on the entry it belongs to. */
    private static volatile String pendingLinkUrl;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    private SharedPreferences prefs() {
        return prefs(getContext());
    }

    static JSONArray storedServers(Context context) {
        try {
            JSONArray entries = new JSONArray(prefs(context).getString(KEY_SERVERS, "[]"));
            java.util.Set<String> claimed = new java.util.HashSet<>();
            boolean changed = false;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String origin = originKey(Uri.parse(entry.getString("url")));
                if (!entry.has("legacySession")) {
                    entry.put("legacySession", claimed.add(origin));
                    changed = true;
                } else if (entry.optBoolean("legacySession")) {
                    claimed.add(origin);
                }
            }
            if (changed) prefs(context).edit().putString(KEY_SERVERS, entries.toString()).apply();
            JSONArray kept = new JSONArray();
            JSONArray removed = new JSONArray(prefs(context).getString(KEY_REMOVED, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                boolean forgotten = false;
                for (int j = 0; j < removed.length(); j++)
                    if (entry.optString("id").equals(removed.getJSONObject(j).optString("id"))) forgotten = true;
                if (!forgotten) kept.put(entry);
            }
            return kept;
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static String removedStorageKeys(Context context, String origin) {
        JSONArray keys = new JSONArray();
        try {
            JSONArray removed = new JSONArray(prefs(context).getString(KEY_REMOVED, "[]"));
            for (int i = 0; i < removed.length(); i++) {
                JSONObject entry = removed.getJSONObject(i);
                if (origin.equals(entry.optString("origin"))) keys.put(entry.getString("key"));
            }
        } catch (JSONException ignored) { }
        return keys.toString();
    }

    private JSONArray servers() { return storedServers(getContext()); }

    @Override
    public void load() {
        bridge.injectScriptBeforeLoad("native/account-session.js");
        String pending = pendingLinkUrl;
        JSONObject active = activeServer(getContext());
        if (pending != null && active != null
            && originKey(Uri.parse(pending)).equals(originKey(Uri.parse(active.optString("url"))))) {
            pendingLinkUrl = null;
            // Posted: runs after the bridge's own first load, and replaces it.
            bridge.getWebView().post(() -> bridge.getWebView().loadUrl(pending));
        }
    }

    // --- Links: App Links, hasht://open, and links tapped in the webview

    /** BridgeActivity also routes the launch intent through here on a cold start. */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        DeepLinks.Target target = DeepLinks.target(intent.getData().toString());
        if (target == null) return;
        // Consumed: recreate() reuses this intent, and must not replay it.
        intent.setData(null);
        route(target);
    }

    /** For HashtWebViewClient: routes the link if it belongs to a saved server or the hosted one. */
    boolean routeIfOwned(Uri url) {
        if (!DeepLinks.isRoutedPath(url.getEncodedPath())) return false;
        DeepLinks.Target target = DeepLinks.target(url.toString());
        if (target == null) return false;
        boolean owned = DeepLinks.HOSTED_ORIGIN.equals(target.origin);
        for (DeepLinks.Entry entry : entries()) {
            if (target.origin.equals(originKey(Uri.parse(entry.url)))) owned = true;
        }
        if (!owned) return false;
        route(target);
        return true;
    }

    private List<DeepLinks.Entry> entries() {
        List<DeepLinks.Entry> entries = new ArrayList<>();
        JSONArray all = servers();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null) entries.add(new DeepLinks.Entry(entry.optString("id"), entry.optString("url")));
        }
        return entries;
    }

    private void route(DeepLinks.Target target) {
        DeepLinks.Decision decision = DeepLinks.decide(target, entries(), prefs().getString(KEY_DEFAULT, null));
        getActivity().runOnUiThread(() -> {
            switch (decision.kind) {
                case OPEN:
                    open(target, decision.id);
                    break;
                case ADD_HOSTED:
                    addAndOpen(target);
                    break;
                case CONFIRM_ADD:
                    String host = Uri.parse(target.origin).getHost();
                    new AlertDialog.Builder(getActivity())
                        .setTitle("Open " + host + "?")
                        .setMessage("Add " + host + " to your servers and open this link?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Add and open", (dialog, which) -> addAndOpen(target))
                        .show();
                    break;
                case CHOOSE:
                    choose(target, decision.ids, false);
                    break;
            }
        });
    }

    /** Picking an entry opens there; "Always use…" asks again and saves the pick as the default. */
    private void choose(DeepLinks.Target target, List<String> ids, boolean remember) {
        List<String> labels = new ArrayList<>();
        List<String> order = new ArrayList<>();
        JSONArray all = servers();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry == null || !ids.contains(entry.optString("id"))) continue;
            String username = entry.optString("username", "");
            labels.add(entry.optString("name") + " · " + (username.isEmpty() ? "Not signed in" : "@" + username));
            order.add(entry.optString("id"));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(getActivity())
            .setTitle(remember ? "Always open links in" : "Open in which account?")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (remember) prefs().edit().putString(KEY_DEFAULT, order.get(which)).apply();
                open(target, order.get(which));
            })
            .setNegativeButton("Cancel", null);
        if (!remember) dialog.setNeutralButton("Always use…", (d, which) -> choose(target, ids, true));
        dialog.show();
    }

    private void open(DeepLinks.Target target, String id) {
        if (id.equals(activeId())) {
            bridge.getWebView().loadUrl(target.url);
            return;
        }
        setActiveId(id);
        pendingLinkUrl = target.url;
        restart();
    }

    private void addAndOpen(DeepLinks.Target target) {
        new Thread(() -> {
            String name = fetchName(target.origin);
            try {
                addAndActivate(getContext(), target.origin, name != null ? name : Uri.parse(target.origin).getHost());
                pendingLinkUrl = target.url;
                restart();
            } catch (JSONException e) {
                // Couldn't store it: the link just doesn't open.
            }
        }).start();
    }

    /** The page reports who is signed in on the active entry; null names clear it. */
    @PluginMethod
    public void setAccount(PluginCall call) {
        String id = activeId();
        if (id != null) setAccount(id, call.getString("username"), call.getString("displayName"));
        call.resolve();
    }

    private void setAccount(String id, String username, String displayName) {
        try {
            JSONArray all = servers();
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (!entry.getString("id").equals(id)) continue;
                boolean signedIn = username != null && !username.isEmpty();
                if (!java.util.Objects.equals(entry.optString("username", null), signedIn ? username : null))
                    attachmentGeneration.incrementAndGet();
                entry.put("username", signedIn ? username : JSONObject.NULL);
                entry.put("displayName", signedIn && displayName != null && !displayName.isEmpty() ? displayName : JSONObject.NULL);
            }
            saveServers(all);
        } catch (JSONException e) {
            // Names only: losing one just shows "Not signed in".
        }
    }

    @PluginMethod
    public void setDefault(PluginCall call) {
        String id = call.getString("id");
        try {
            if (id != null && find(id) == null) {
                call.reject("unknown server");
                return;
            }
        } catch (JSONException e) {
            call.reject("unknown server");
            return;
        }
        SharedPreferences.Editor editor = prefs().edit();
        if (id == null) editor.remove(KEY_DEFAULT); else editor.putString(KEY_DEFAULT, id);
        editor.apply();
        call.resolve();
    }

    static String pushKey(JSONObject entry) {
        return entry.optBoolean("legacySession") ? originKey(Uri.parse(entry.optString("url"))) : entry.optString("id");
    }

    static String serverIdForPushKey(Context context, String key) {
        JSONArray entries = storedServers(context);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && key.equals(pushKey(entry))) return entry.optString("id");
        }
        return null;
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
            JSONArray all = storedServers(context);
            for (int i = 0; i < all.length(); i++) {
                JSONObject entry = all.getJSONObject(i);
                if (id.equals(entry.getString("id"))) return entry;
            }
        } catch (JSONException e) {
            // Corrupt store: the user picks again.
        }
        return null;
    }

    /** Adds and activates; the caller recreates the activity to load it. */
    static String addAndActivate(Context context, String url, String name) throws JSONException {
        SharedPreferences prefs = prefs(context);
        JSONArray all = storedServers(context);
        JSONObject entry = new JSONObject();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("url", url);
        entry.put("name", name);
        entry.put("legacySession", false);
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
        attachmentGeneration.incrementAndGet();
        String active = activeId();
        if (active != null) setAccount(active, null, null);
        String origin = NativePushPlugin.activeAccountKey(getContext());
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
        String defaultId = prefs().getString(KEY_DEFAULT, null);
        result.put("defaultId", defaultId != null ? defaultId : JSONObject.NULL);
        call.resolve(result);
    }

    @PluginMethod
    public void add(PluginCall call) {
        attachmentGeneration.incrementAndGet();
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
            entry.put("legacySession", false);

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
        attachmentGeneration.incrementAndGet();
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

    @Override
    protected void handleOnDestroy() {
        attachmentGeneration.incrementAndGet();
        super.handleOnDestroy();
    }

    private final AtomicInteger attachmentGeneration = new AtomicInteger();
    private final AtomicBoolean attachmentSaving = new AtomicBoolean();

    private String attachmentAccount(PluginCall call) throws Exception {
        JSONObject active = activeServer(getContext());
        if (active == null || bridge.getAppUrl() == null
            || !originKey(Uri.parse(active.optString("url"))).equals(originKey(Uri.parse(bridge.getAppUrl()))))
            throw new IllegalArgumentException("Connect to a server before opening attachments.");
        AttachmentFiles.url(call.getString("url"), "https".equalsIgnoreCase(Uri.parse(active.optString("url")).getScheme()));
        return active.getString("id");
    }

    @PluginMethod
    public void openAttachment(PluginCall call) {
        try {
            attachmentAccount(call);
            Uri url = Uri.parse(call.getString("url"));
            int generation = attachmentGeneration.get();
            getActivity().runOnUiThread(() -> {
                try {
                    if (generation != attachmentGeneration.get()) { call.reject("The active account changed. Open the attachment again."); return; }
                    // Select a browser explicitly: an instance URL must not route back into this app.
                    String browser = androidx.browser.customtabs.CustomTabsClient.getPackageName(getContext(), null);
                    if (browser == null) { call.reject("Install a browser to open attachments."); return; }
                    if (LinkOpener.SYSTEM.equals(LinkOpener.mode(getContext()))) {
                        getActivity().startActivity(new Intent(Intent.ACTION_VIEW, url).setPackage(browser));
                    } else {
                        androidx.browser.customtabs.CustomTabsIntent tab = new androidx.browser.customtabs.CustomTabsIntent.Builder().build();
                        tab.intent.setPackage(browser);
                        tab.launchUrl(getActivity(), url);
                    }
                    call.resolve();
                } catch (Exception error) { call.reject("Could not open the attachment in a browser.", error); }
            });
        } catch (Exception error) { call.reject(error.getMessage(), error); }
    }

    @PluginMethod
    public void saveAttachment(PluginCall call) {
        boolean admitted = false;
        try {
            String account = attachmentAccount(call);
            if (!attachmentSaving.compareAndSet(false, true)) { call.reject("Finish the current attachment save first."); return; }
            admitted = true;
            call.getData().put("attachmentAccount", account);
            call.getData().put("attachmentGeneration", attachmentGeneration.get());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, AttachmentFiles.filename(call.getString("filename")));
            startActivityForResult(call, intent, "attachmentDestination");
        } catch (Exception error) { if (admitted) attachmentSaving.set(false); call.reject("Could not open the save dialog.", error); }
    }

    @ActivityCallback
    private void attachmentDestination(PluginCall call, ActivityResult result) {
        if (call == null) { attachmentSaving.set(false); return; }
        Uri destination = result.getData() == null ? null : result.getData().getData();
        if (result.getResultCode() != Activity.RESULT_OK || destination == null) {
            attachmentSaving.set(false);
            JSObject cancelled = new JSObject(); cancelled.put("cancelled", true); call.resolve(cancelled); return;
        }
        try {
            if (!attachmentAccount(call).equals(call.getString("attachmentAccount"))
                || attachmentGeneration.get() != call.getInt("attachmentGeneration", -1))
                throw new IllegalStateException("The active account changed. Save the attachment again.");
        } catch (Exception error) {
            try { DocumentsContract.deleteDocument(getContext().getContentResolver(), destination); } catch (Exception ignored) { }
            attachmentSaving.set(false); call.reject(error.getMessage(), error); return;
        }
        // Do not block Capacitor's shared plugin thread during a large transfer.
        new Thread(() -> {
            try (OutputStream output = getContext().getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new java.io.IOException("Could not open the selected file.");
                AttachmentFiles.download(call.getString("url"), output,
                    () -> attachmentGeneration.get() == call.getInt("attachmentGeneration", -1));
                call.resolve();
            } catch (Exception error) {
                try { DocumentsContract.deleteDocument(getContext().getContentResolver(), destination); } catch (Exception ignored) { }
                call.reject("Could not save the attachment. " + error.getMessage(), error);
            } finally { attachmentSaving.set(false); }
        }, "hasht-attachment-save").start();
    }

    @PluginMethod
    public void openSecurityManagement(PluginCall call) {
        String ticket = call.getString("ticket");
        JSONObject active = activeServer(getContext());
        if (ticket == null || !ticket.matches("[0-9a-fA-F]{64}") || active == null) {
            call.reject("A server and valid management ticket are required.");
            return;
        }
        Uri base = Uri.parse(active.optString("url"));
        if (!"https".equals(base.getScheme())) { call.reject("A secure server is required."); return; }
        Uri target = Uri.parse(originKey(base)).buildUpon().path("/")
            .appendQueryParameter("security_management", "1").fragment("ticket=" + ticket).build();
        getActivity().runOnUiThread(() -> {
            try {
                new androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(getActivity(), target);
                call.resolve();
            } catch (android.content.ActivityNotFoundException error) {
                call.reject("Install a browser to manage passkeys.");
            }
        });
    }

    @PluginMethod
    public void openSecuritySettings(PluginCall call) {
        String userId = call.getString("userId");
        JSONObject active = activeServer(getContext());
        if (userId == null || !userId.matches("[0-9a-fA-F-]{36}") || active == null) {
            call.reject("An account and server are required.");
            return;
        }
        Uri base = Uri.parse(active.optString("url"));
        if (!"https".equals(base.getScheme())) { call.reject("A secure server is required."); return; }
        Uri target = Uri.parse(originKey(base)).buildUpon().path("/")
            .appendQueryParameter("manage_security", userId).build();
        getActivity().runOnUiThread(() -> {
            try {
                new androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(getContext(), target);
                call.resolve();
            } catch (android.content.ActivityNotFoundException error) {
                call.reject("Install a browser to manage passkeys.");
            }
        });
    }

    @PluginMethod
    public void remove(PluginCall call) {
        attachmentGeneration.incrementAndGet();
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
                else {
                    String origin = originKey(Uri.parse(entry.getString("url")));
                    String key = entry.optBoolean("legacySession") ? "chat_auth" : "hasht.account." + id + ".auth";
                    JSONArray removed = new JSONArray(prefs().getString(KEY_REMOVED, "[]"));
                    removed.put(new JSONObject().put("id", id).put("origin", origin).put("key", key));
                    if (!prefs().edit().putString(KEY_REMOVED, removed.toString()).commit()) {
                        call.reject("Could not save server removal."); return;
                    }
                    NativePushPlugin.unregister(getContext(), entry.optBoolean("legacySession") ? origin : id);
                    getActivity().runOnUiThread(() -> clearSavedAccount(origin, key));
                }
            }
            NativeAuthPlugin.forgetAccount(id);
            saveServers(kept);
            if (id.equals(activeId())) setActiveId(null);
            if (id.equals(prefs().getString(KEY_DEFAULT, null))) prefs().edit().remove(KEY_DEFAULT).apply();
            call.resolve();
        } catch (JSONException e) {
            call.reject("could not remove that server");
        }
    }

    private void clearSavedAccount(String origin, String key) {
        android.webkit.WebView cleanup = new android.webkit.WebView(getContext());
        cleanup.getSettings().setJavaScriptEnabled(true);
        cleanup.getSettings().setDomStorageEnabled(true);
        final boolean[] finished = { false };
        Runnable finish = () -> {
            if (!finished[0]) { finished[0] = true; cleanup.stopLoading(); cleanup.destroy(); }
        };
        cleanup.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                return !origin.equals(originKey(request.getUrl()));
            }
            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                if (finished[0] || !origin.equals(originKey(Uri.parse(url)))) return;
                String script = "(() => { const key = " + JSONObject.quote(key) + "; "
                    + "const raw = localStorage.getItem(key); localStorage.removeItem(key); "
                    + "try { return JSON.parse(raw || 'null')?.token || ''; } catch (_) { return ''; } })()";
                view.evaluateJavascript(script, value -> {
                    if (finished[0]) return;
                    try {
                        String token = new JSONArray("[" + value + "]").optString(0);
                        if (!token.isEmpty()) new Thread(() -> revokeRemovedSession(origin, token)).start();
                    } catch (JSONException ignored) { }
                    finish.run();
                });
            }
        });
        cleanup.loadUrl(origin + "/api/health");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(finish, 12000);
    }

    private void revokeRemovedSession(String origin, String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/api/auth/logout").openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.getResponseCode();
        } catch (java.io.IOException ignored) { }
        finally { if (connection != null) connection.disconnect(); }
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
        attachmentGeneration.incrementAndGet();
        returnToWizard();
        call.resolve();
    }

    private void returnToWizard() {
        returnToWizard(getActivity());
    }

    static void returnToWizard(android.app.Activity activity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ACTIVE).apply();
        activity.runOnUiThread(activity::recreate);
    }

    /** Where links leaving the instance open; see LinkOpener. */
    @PluginMethod
    public void getLinkMode(PluginCall call) {
        JSObject result = new JSObject();
        result.put("mode", LinkOpener.mode(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void setLinkMode(PluginCall call) {
        String mode = call.getString("mode");
        if (!LinkOpener.IN_APP.equals(mode) && !LinkOpener.SYSTEM.equals(mode)) {
            call.reject("mode must be \"in-app\" or \"system\"");
            return;
        }
        LinkOpener.setMode(getContext(), mode);
        call.resolve();
    }

    /** Rebuilds the bridge against the config the new active server implies. */
    private void restart() {
        getActivity().runOnUiThread(() -> getActivity().recreate());
    }
}
