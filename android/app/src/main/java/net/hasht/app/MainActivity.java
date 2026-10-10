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

        // After super: the App plugin registers its own callback during
        // onCreate, and the most recently added one runs first. Its handler
        // swallows back when there is no history, which is what left users
        // stuck on the picker.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView webView = getBridge().getWebView();
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    // Leave like any other app, but keep the process: finish()
                    // would drop a live call's webview.
                    moveTaskToBack(true);
                }
            }
        });
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
