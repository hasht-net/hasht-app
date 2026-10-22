package net.hasht.app;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native escape hatch: it must work even when the remote document has no JavaScript. */
final class LoadRecovery {
    private final MainActivity activity;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout panel;
    private final TextView message;
    private final Button retry;
    private String url;
    private int generation;
    private boolean pending;
    private boolean failed;

    LoadRecovery(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int spacing = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(spacing, spacing, spacing, spacing);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            panel.setPadding(spacing + bars.left, spacing, spacing + bars.right, spacing + bars.bottom);
            return insets;
        });
        panel.setBackgroundColor(Color.rgb(245, 245, 245));
        message = new TextView(activity);
        message.setTextColor(Color.BLACK);
        panel.addView(message);
        retry = new Button(activity);
        retry.setText("Retry");
        retry.setOnClickListener(v -> {
            String target = url;
            webView.stopLoading();
            started(target);
            webView.loadUrl(target);
        });
        panel.addView(retry);
        Button servers = new Button(activity);
        servers.setText("Back to servers");
        servers.setOnClickListener(v -> returnToServers());
        panel.addView(servers);
        android.widget.FrameLayout.LayoutParams layout = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        activity.addContentView(panel, layout);
        androidx.core.view.ViewCompat.requestApplyInsets(panel);
        panel.setVisibility(View.GONE);
    }

    void started(String target) {
        if (target == null || !remote(target)) return;
        url = target;
        pending = true;
        failed = false;
        int attempt = ++generation;
        panel.setVisibility(View.GONE);
        handler.postDelayed(() -> {
            if (attempt != generation || !pending) return;
            message.setText("Connecting to " + android.net.Uri.parse(url).getHost() + "…");
            retry.setVisibility(View.GONE);
            panel.setVisibility(View.VISIBLE);
        }, 1500);
        handler.postDelayed(() -> {
            if (attempt == generation && pending) error(url, "The server took too long to load.");
        }, 20000);
    }

    private boolean remote(String target) {
        return ServerConfigPlugin.activeServer(activity) != null
            && (target.startsWith("https://") || target.startsWith("http://"));
    }

    void error(String target, String reason) {
        if (!pending || target == null) return;
        if (!target.equals(url)) {
            // WebView may report the final redirect URL without another start.
            if (!target.equals(webView.getUrl())) return;
            url = target;
        }
        pending = false;
        failed = true;
        ++generation;
        webView.stopLoading();
        message.setText("Couldn't load " + android.net.Uri.parse(url).getHost() + ".\n" + reason);
        retry.setVisibility(View.VISIBLE);
        panel.setVisibility(View.VISIBLE);
    }

    void finished(String target) {
        if (!pending || target == null || !target.equals(webView.getUrl())) return;
        // Redirect chains do not always produce another onPageStarted callback.
        url = target;
        checkReady(generation);
    }

    private void checkReady(int attempt) {
        if (attempt != generation || !pending) return;
        org.json.JSONObject server = ServerConfigPlugin.activeServer(activity);
        boolean instancePage = server != null && ServerConfigPlugin.originKey(android.net.Uri.parse(url)).equals(
            ServerConfigPlugin.originKey(android.net.Uri.parse(server.optString("url"))));
        // An SSO redirect is a real document but does not mount Hasht's Vue root.
        String readiness = instancePage
            ? "!!(document.getElementById('app') && document.getElementById('app').childElementCount)"
            : "document.readyState === 'complete'";
        webView.evaluateJavascript(readiness, ready -> {
                if (attempt != generation || !pending) return;
                String current = webView.getUrl();
                if ("true".equals(ready) && current != null && ServerConfigPlugin.originKey(android.net.Uri.parse(url)).equals(
                    ServerConfigPlugin.originKey(android.net.Uri.parse(current)))) {
                    pending = false;
                    panel.setVisibility(View.GONE);
                    ++generation;
                } else handler.postDelayed(() -> checkReady(attempt), 250);
            });
    }

    boolean handleBack() {
        if (!pending && !failed) return false;
        returnToServers();
        return true;
    }

    private void returnToServers() {
        ++generation;
        pending = false;
        failed = false;
        webView.stopLoading();
        ServerConfigPlugin.returnToWizard(activity);
    }

    void destroy() {
        ++generation;
        handler.removeCallbacksAndMessages(null);
    }
}
