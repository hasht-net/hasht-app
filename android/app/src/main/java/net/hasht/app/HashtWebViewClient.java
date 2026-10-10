package net.hasht.app;

import android.net.Uri;
import android.webkit.WebResourceRequest;
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
