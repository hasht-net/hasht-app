package net.hasht.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AccountIsolationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String ORIGIN = "https://account-isolation-test.invalid";

    @Test public void migrationKeepsOneLegacyAccountAndNewEntriesStayIsolated() throws Exception {
        String before = ServerConfigPlugin.prefs(context).getString("servers", null);
        try {
            JSONArray entries = new JSONArray()
                .put(entry("first", null)).put(entry("duplicate", null))
                .put(new JSONObject().put("id", "other").put("url", "https://other.invalid").put("name", "Other"))
                .put(entry("new", false));
            ServerConfigPlugin.prefs(context).edit().putString("servers", entries.toString()).commit();
            JSONArray migrated = ServerConfigPlugin.storedServers(context);
            assertTrue(migrated.getJSONObject(0).getBoolean("legacySession"));
            assertFalse(migrated.getJSONObject(1).getBoolean("legacySession"));
            assertTrue(migrated.getJSONObject(2).getBoolean("legacySession"));
            assertFalse(migrated.getJSONObject(3).getBoolean("legacySession"));
            assertEquals(ORIGIN, ServerConfigPlugin.pushKey(migrated.getJSONObject(0)));
            assertEquals("duplicate", ServerConfigPlugin.pushKey(migrated.getJSONObject(1)));
            assertEquals("duplicate", ServerConfigPlugin.serverIdForPushKey(context, "duplicate"));
            assertEquals("first", ServerConfigPlugin.serverIdForPushKey(context, ORIGIN));
            assertEquals(migrated.toString(), ServerConfigPlugin.storedServers(context).toString());
        } finally {
            ServerConfigPlugin.prefs(context).edit().putString("servers", before).commit();
        }
    }

    @Test public void pushRegistrationsKeepTheBackendUrlSeparateFromTheAccountKey() throws Exception {
        PushStore.Registration registration = new PushStore.Registration(
            ORIGIN, "https://relay.invalid", "registration", "https://relay.invalid/push", new byte[0], new byte[0], new byte[0]);
        registration.accountKey = "second";
        PushStore.Registration restored = PushStore.Registration.fromJson(registration.toJson());
        assertEquals(ORIGIN, restored.origin);
        assertEquals("second", restored.scope());
        JSONObject legacy = registration.toJson();
        legacy.remove("accountKey");
        assertEquals(ORIGIN, PushStore.Registration.fromJson(legacy).scope());
    }

    @Test public void webViewRestoresEachSavedAccountBeforeFrontendCodeRuns() throws Exception {
        String script;
        try (java.io.InputStream input = context.getAssets().open("public/native/account-session.js")) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        WebView[] holder = new WebView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            holder[0] = new WebView(context);
            holder[0].getSettings().setJavaScriptEnabled(true);
            holder[0].getSettings().setDomStorageEnabled(true);
        });
        WebView web = holder[0];
        try {
            assertEquals("legacy", load(web, entry("first", true), "", "localStorage.clear(); localStorage.setItem('chat_auth','legacy'); localStorage.getItem('chat_auth')"));
            assertNull(load(web, entry("second", false), script, "localStorage.getItem('chat_auth')"));
            assertEquals("second-user", load(web, entry("second", false), script, "localStorage.setItem('chat_auth','second-user'); localStorage.getItem('chat_auth')"));
            assertEquals("legacy", load(web, entry("first", true), script, "localStorage.getItem('chat_auth')"));
            assertNull(load(web, entry("third", false), script, "localStorage.getItem('chat_auth')"));
            assertEquals("second-user", load(web, entry("second", false), script, "localStorage.getItem('chat_auth')"));
            assertNull(load(web, entry("first", true), script, "localStorage.clear(); localStorage.getItem('chat_auth')"));
            assertEquals("second-user", load(web, entry("second", false), script, "localStorage.getItem('chat_auth')"));
            assertNull(load(web, entry("second", false), script, "localStorage.removeItem('chat_auth'); localStorage.getItem('chat_auth')"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(web::destroy);
        }
    }

    private static JSONObject entry(String id, Boolean legacy) throws Exception {
        JSONObject entry = new JSONObject().put("id", id).put("url", ORIGIN).put("name", "Test");
        if (legacy != null) entry.put("legacySession", legacy);
        return entry;
    }

    public static final class Result {
        final CountDownLatch ready = new CountDownLatch(1);
        String value;
        @JavascriptInterface public void finish(String value) { this.value = value; ready.countDown(); }
    }

    private static String load(WebView web, JSONObject entry, String script, String expression) throws Exception {
        Result result = new Result();
        String html = "<!doctype html><html><head><script>" + script
            + ";Result.finish(JSON.stringify((()=>{" + "return eval(" + JSONObject.quote(expression) + ");})()));</script></head><body></body></html>";
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            web.addJavascriptInterface(new MainActivity.AccountContext(entry), "HashtAccount");
            web.addJavascriptInterface(result, "Result");
            web.loadDataWithBaseURL(ORIGIN, html, "text/html", "UTF-8", null);
        });
        assertTrue("WebView did not execute the account script", result.ready.await(15, TimeUnit.SECONDS));
        Object decoded = new org.json.JSONTokener(result.value).nextValue();
        return decoded == JSONObject.NULL ? null : (String) decoded;
    }
}
