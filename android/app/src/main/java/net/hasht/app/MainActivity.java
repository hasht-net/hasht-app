package net.hasht.app;

import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Before super: BridgeActivity builds the bridge from `config` during
        // onCreate, and the active server has to be in it by then for Capacitor
        // to treat that origin as the app and inject the bridge into it.
        NativePushPlugin.prepareLaunch(this);
        config = ServerConfigPlugin.runtimeConfig(this);
        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(CallSessionPlugin.class);
        registerPlugin(NativeAuthPlugin.class);
        registerPlugin(NativePushPlugin.class);
        super.onCreate(savedInstanceState);
        getBridge().setWebViewClient(new HashtWebViewClient(getBridge()));

        // The App plugin's own back handler is off (capacitor.config.ts); it
        // swallowed back with no history, which left users stuck on the picker.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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
