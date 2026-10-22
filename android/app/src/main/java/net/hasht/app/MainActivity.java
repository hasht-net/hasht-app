package net.hasht.app;

import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private LoadRecovery loadRecovery;
    @Override
    protected void load() {
        android.webkit.WebView webView = findViewById(com.getcapacitor.android.R.id.webview);
        org.json.JSONObject entry = ServerConfigPlugin.activeServer(this);
        if (entry != null) webView.addJavascriptInterface(new AccountContext(this, entry), "HashtAccount");
        super.load();
    }

    public static final class AccountContext {
        private final String origin;
        private final String id;
        private final String storageKey;
        private final String removedKeys;

        AccountContext(android.content.Context context, org.json.JSONObject entry) {
            origin = ServerConfigPlugin.originKey(android.net.Uri.parse(entry.optString("url")));
            id = entry.optString("id");
            removedKeys = ServerConfigPlugin.removedStorageKeys(context, origin);
            storageKey = entry.optBoolean("legacySession") ? "chat_auth" : "hasht.account." + id + ".auth";
        }

        @android.webkit.JavascriptInterface public String getRemovedStorageKeys() { return removedKeys; }
        @android.webkit.JavascriptInterface public String getOrigin() { return origin; }
        @android.webkit.JavascriptInterface public String getAccountID() { return id; }
        @android.webkit.JavascriptInterface public String getStorageKey() { return storageKey; }
    }

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Before super: BridgeActivity builds the bridge from `config` during
        // onCreate, and the active server has to be in it by then for Capacitor
        // to treat that origin as the app and inject the bridge into it.
        NativePushPlugin.prepareLaunch(this);
        ShareTargetPlugin.prepareLaunch(this, getIntent(), savedInstanceState != null);
        config = ServerConfigPlugin.runtimeConfig(this);
        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(CallSessionPlugin.class);
        registerPlugin(NativeAuthPlugin.class);
        registerPlugin(NativePushPlugin.class);
        registerPlugin(ShareTargetPlugin.class);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Let the system splash leave on the first frame; the web app owns loading.
            androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        }
        super.onCreate(savedInstanceState);
        loadRecovery = new LoadRecovery(this, getBridge().getWebView());
        getBridge().setWebViewClient(new HashtWebViewClient(getBridge(), loadRecovery));
        loadRecovery.started(getBridge().getAppUrl());
        loadRecovery.finished(getBridge().getAppUrl());

        // The App plugin's own back handler is off (capacitor.config.ts); it
        // swallowed back with no history, which left users stuck on the picker.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (loadRecovery.handleBack()) return;
                WebView webView = getBridge().getWebView();
                webView.evaluateJavascript(DISMISS_TOP_LAYER, handled -> {
                    if ("true".equals(handled)) return;
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        // Leave like any other app, but keep the process: finish()
                        // would drop a live call's webview.
                        moveTaskToBack(true);
                    }
                });
            }
        });
    }

    /**
     * Back closes whatever the page has open first, the way Escape does on
     * desktop. The frontend cancels an Escape it acted on, so that is the
     * signal; a page that ignores it (the picker, an older instance) falls
     * through to history and then leaving.
     */
    private static final String DISMISS_TOP_LAYER =
        "(function () {"
            + "var e = new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape',"
            + " keyCode: 27, bubbles: true, cancelable: true});"
            + "(document.activeElement || document.body || document).dispatchEvent(e);"
            + "return e.defaultPrevented;"
            + "})()";

    @Override
    public void onDestroy() {
        if (loadRecovery != null) loadRecovery.destroy();
        super.onDestroy();
    }

    /** Whether the app is on screen; pushes for the instance shown are skipped. */
    private static volatile boolean visible;

    static boolean isVisible() {
        return visible;
    }

    @Override
    public void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    public void onStop() {
        super.onStop();
        visible = false;
    }
}
