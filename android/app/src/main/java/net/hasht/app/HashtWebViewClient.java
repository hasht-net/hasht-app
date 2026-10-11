package net.hasht.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

/**
 * Capacitor's allowNavigation has to be '*' here (the instance is arbitrary),
 * which also makes it load every link in the webview. Links that leave the
 * instance are intercepted first and opened per LinkOpener.
 */
class HashtWebViewClient extends BridgeWebViewClient {
    private final Bridge bridge;

    HashtWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    // The frontend is remote, so its stylesheet can't be changed from here.
    // Without this Chromium paints a hard blue rectangle over whatever is
    // tapped, which reads as a web page rather than an app.
    private static final String NO_TAP_HIGHLIGHT =
        "(function(){if(document.getElementById('hasht-no-tap-highlight'))return;"
            + "var s=document.createElement('style');s.id='hasht-no-tap-highlight';"
            + "s.textContent='*{-webkit-tap-highlight-color:transparent}';"
            + "(document.head||document.documentElement).appendChild(s);})();";

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        super.onPageCommitVisible(view, url);
        view.evaluateJavascript(NO_TAP_HIGHLIGHT, null);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        view.evaluateJavascript(NO_TAP_HIGHLIGHT, null);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        String path = url.getPath();
        if (path != null && path.startsWith(ShareTargetPlugin.PATH_PREFIX)
            && "GET".equals(request.getMethod()) && isAppOrigin(url)) {
            return ShareTargetPlugin.serve(url);
        }
        return super.shouldInterceptRequest(view, request);
    }

    private boolean isAppOrigin(Uri url) {
        Uri app = Uri.parse(bridge.getAppUrl());
        return ServerConfigPlugin.originKey(url).equals(ServerConfigPlugin.originKey(app));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        // Server-side redirects stay in the webview so an instance can bounce
        // through its own SSO; only a navigation the page started is a "link".
        if (request.isForMainFrame() && !request.isRedirect()
            && LinkOpener.leavesApp(url, Uri.parse(bridge.getAppUrl()))) {
            LinkOpener.open(bridge.getActivity(), url);
            return true;
        }
        return super.shouldOverrideUrlLoading(view, request);
    }
}
