package net.hasht.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.browser.auth.AuthTabIntent;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Passkey sign-in handed off to the browser.
 *
 * Android WebView can only run WebAuthn for a relying party linked to the app
 * through Digital Asset Links, which no arbitrary self-hosted instance is. The
 * browser can, so the instance's approve page runs there and sends a one-time
 * code back over hasht://auth, which is redeemed here for a session.
 *
 * Opens an Auth Tab where the browser supports one (Chrome 137+), which hands
 * the callback straight back as an activity result. Otherwise a Custom Tab, and
 * the callback arrives as a hasht:// intent; any app can claim that scheme, but
 * the code is useless without the verifier, which never leaves this process.
 *
 * The server side is auth::native in the chat repo; mirrors hasht-desktop's
 * nativeAuth.ts. Two ways in:
 * - signIn(), from the instance's own login screen.
 * - hasht://signin?server=origin, from the web app's settings on this device.
 *   Any page can send that link naming any host, so it never adds or switches
 *   a server until the user confirms and sign-in succeeds. The auth is then
 *   parked for that server and collected by the page via takePendingAuth().
 */
@CapacitorPlugin(name = "NativeAuth")
public class NativeAuthPlugin extends Plugin {

    private static final String SCHEME = "hasht";

    /**
     * Auth for a server the activity is about to load, keyed by server id.
     * Static: loading a server recreates the activity and this plugin with it.
     */
    private static final Map<String, JSObject> pendingAuth = new HashMap<>();

    private interface Outcome {
        void signedIn(JSObject auth);

        void cancelled();

        void failed(String message);
    }

    private static final class Handoff {
        final String base;
        final Outcome outcome;
        final boolean confirmHost;
        String requestId;
        String verifier;
        boolean inBrowser;
        boolean viaAuthTab;

        Handoff(String base, Outcome outcome, boolean confirmHost) {
            this.base = base;
            this.outcome = outcome;
            this.confirmHost = confirmHost;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> authTab;
    private Handoff current;

    @Override
    public void load() {
        // Has to be registered before the activity starts, which load() is.
        authTab = AuthTabIntent.registerActivityResultLauncher(getActivity(), this::onAuthTabResult);
    }

    /** BridgeActivity also routes the launch intent through here on a cold start. */
    @Override
    protected void handleOnNewIntent(Intent intent) {
        handleIntent(intent);
    }

    /**
     * A Custom Tab gives no result when it closes, so coming back to the app
     * without the callback having arrived first means the user backed out.
     * The callback's intent is always delivered before onResume.
     */
    @Override
    protected void handleOnResume() {
        final Handoff handoff = current;
        if (handoff == null || !handoff.inBrowser || handoff.viaAuthTab) return;
        main.postDelayed(() -> {
            if (current == handoff && handoff.inBrowser) finish(handoff, h -> h.outcome.cancelled());
        }, 1000);
    }

    // --- From the page

    @PluginMethod
    public void signIn(PluginCall call) {
        JSONObject active = ServerConfigPlugin.activeServer(getContext());
        if (active == null) {
            call.reject("No server is selected.");
            return;
        }
        // The user just tapped Sign in on this server's own page: nothing to confirm.
        run(active.optString("url"), false, new Outcome() {
            @Override
            public void signedIn(JSObject auth) {
                JSObject result = new JSObject();
                result.put("auth", auth);
                call.resolve(result);
            }

            @Override
            public void cancelled() {
                call.reject("Sign-in cancelled.", "cancelled");
            }

            @Override
            public void failed(String message) {
                call.reject(message);
            }
        });
    }

    @PluginMethod
    public void takePendingAuth(PluginCall call) {
        JSONObject active = ServerConfigPlugin.activeServer(getContext());
        JSObject auth = active == null ? null : pendingAuth.remove(active.optString("id"));
        JSObject result = new JSObject();
        result.put("auth", auth != null ? auth : JSONObject.NULL);
        call.resolve(result);
    }

    // --- Deep links

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !SCHEME.equals(data.getScheme())) return;
        // Consumed: recreate() reuses this intent, and must not replay it.
        intent.setData(null);
        if ("auth".equals(data.getHost())) {
            onCallback(data);
        } else if ("signin".equals(data.getHost())) {
            onSignInLink(data);
        }
    }

    private void onSignInLink(Uri link) {
        final Uri origin = httpsOrigin(link.getQueryParameter("server"));
        if (origin == null) return;
        run(origin.toString(), true, new Outcome() {
            @Override
            public void signedIn(JSObject auth) {
                adopt(auth, origin);
            }

            @Override
            public void cancelled() {}

            @Override
            public void failed(String message) {
                showMessage("Couldn't sign in", message);
            }
        });
    }

    /** A browser handoff creates an account without replacing a saved one. */
    private void adopt(JSObject auth, Uri origin) {
        new Thread(() -> {
            try {
                String name = ServerConfigPlugin.fetchName(origin.toString());
                final String serverId = ServerConfigPlugin.addAndActivate(
                    getContext(), origin.toString(), name != null ? name : origin.getHost());
                main.post(() -> {
                    pendingAuth.put(serverId, auth);
                    getActivity().recreate();
                });
            } catch (Exception e) {
                main.post(() -> showMessage("Couldn't sign in", "Couldn't add that server."));
            }
        }).start();
    }

    /** https only, reduced to its origin: the link's sender is untrusted. */
    private static Uri httpsOrigin(String raw) {
        if (raw == null) return null;
        Uri url = Uri.parse(raw);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getHost().isEmpty()) {
            return null;
        }
        String port = url.getPort() == -1 ? "" : ":" + url.getPort();
        return Uri.parse("https://" + url.getHost() + port);
    }

    // --- The handoff

    private void run(String base, boolean confirmHost, Outcome outcome) {
        if (current != null) {
            outcome.failed("A sign-in is already in progress.");
            return;
        }
        final Handoff handoff = new Handoff(base, outcome, confirmHost);
        current = handoff;
        new Thread(() -> {
            try {
                handoff.verifier = hex(randomBytes(32));
                JSONObject body = new JSONObject();
                body.put("challenge", hex(sha256(handoff.verifier)));
                body.put("device_label", "Hasht on " + deviceName());

                Response res = post(base + "/api/auth/native/start", body);
                if (res.status == 404) {
                    finish(handoff, h -> h.outcome.failed(
                        "This server hasn't enabled app sign-in. Ask the admin to turn it on, or sign in with a password."));
                    return;
                }
                JSONObject json = res.status == 200 ? new JSONObject(res.body) : null;
                // match_code is deprecated and ignored: Authorize now asks for a step-up.
                if (json == null || !json.has("request_id")) {
                    finish(handoff, h -> h.outcome.failed("Couldn't start sign-in (" + res.status + ")."));
                    return;
                }
                handoff.requestId = json.getString("request_id");
                main.post(() -> {
                    if (handoff.confirmHost) confirm(handoff);
                    else openBrowser(handoff);
                });
            } catch (Exception e) {
                finish(handoff, h -> h.outcome.failed("Couldn't reach that server."));
            }
        }).start();
    }

    /** The confirm a hasht://signin link must never skip: any page can send one naming any host. */
    private void confirm(Handoff handoff) {
        String host = Uri.parse(handoff.base).getHost();
        new AlertDialog.Builder(getActivity())
            .setTitle("Sign in to " + host + "?")
            .setMessage("Your browser will open so you can sign in and approve.")
            .setNegativeButton("Cancel", (dialog, which) -> finish(handoff, h -> h.outcome.cancelled()))
            .setOnCancelListener(dialog -> finish(handoff, h -> h.outcome.cancelled()))
            .setPositiveButton("Continue", (dialog, which) -> openBrowser(handoff))
            .show();
    }

    private void openBrowser(Handoff handoff) {
        Uri url = Uri.parse(handoff.base).buildUpon()
            .path("/")
            .clearQuery()
            .appendQueryParameter("native_auth", handoff.requestId)
            .build();
        String provider = CustomTabsClient.getPackageName(getContext(), null);
        handoff.inBrowser = true;
        try {
            if (provider != null && CustomTabsClient.isAuthTabSupported(getContext(), provider)) {
                handoff.viaAuthTab = true;
                AuthTabIntent tab = new AuthTabIntent.Builder().build();
                tab.intent.setPackage(provider);
                tab.launch(authTab, url, SCHEME);
            } else {
                CustomTabsIntent tab = new CustomTabsIntent.Builder().build();
                if (provider != null) tab.intent.setPackage(provider);
                tab.launchUrl(getActivity(), url);
            }
        } catch (ActivityNotFoundException e) {
            finish(handoff, h -> h.outcome.failed("No browser is available to sign in with."));
        }
    }

    private void onAuthTabResult(AuthTabIntent.AuthResult result) {
        Handoff handoff = current;
        if (handoff == null || !handoff.viaAuthTab) return;
        if (result.resultCode == AuthTabIntent.RESULT_OK && result.resultUri != null) {
            if (!onCallback(result.resultUri)) {
                finish(handoff, h -> h.outcome.failed("That sign-in didn't complete. Try again."));
            }
        } else {
            finish(handoff, h -> h.outcome.cancelled());
        }
    }

    /**
     * Redeems a hasht://auth callback. Returns false for anything this
     * handoff isn't waiting for, so a stray or forged link is a no-op.
     */
    private boolean onCallback(Uri callback) {
        final Handoff handoff = current;
        final String code = callback.getQueryParameter("code");
        if (handoff == null || handoff.requestId == null || code == null
            || !handoff.requestId.equals(callback.getQueryParameter("request_id"))) {
            return false;
        }
        handoff.inBrowser = false;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("request_id", handoff.requestId);
                body.put("code", code);
                body.put("verifier", handoff.verifier);
                Response res = post(handoff.base + "/api/auth/native/exchange", body);
                if (res.status != 200) throw new Exception("exchange returned " + res.status);
                JSObject auth = new JSObject(res.body);
                finish(handoff, h -> h.outcome.signedIn(auth));
            } catch (Exception e) {
                finish(handoff, h -> h.outcome.failed("That sign-in didn't work. Try again from the app."));
            }
        }).start();
        return true;
    }

    private interface Report {
        void to(Handoff handoff);
    }

    /** Ends the handoff once, on the main thread. */
    private void finish(Handoff handoff, Report report) {
        main.post(() -> {
            if (current != handoff) return;
            current = null;
            report.to(handoff);
        });
    }

    // --- Plumbing

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(getActivity())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        if (maker.isEmpty() || model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Response post(String url, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) text.append(line);
                }
            }
            return new Response(status, text.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}
