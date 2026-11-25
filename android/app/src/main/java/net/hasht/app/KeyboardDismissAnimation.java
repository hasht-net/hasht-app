package net.hasht.app;

import android.os.Build;
import android.view.View;
import android.webkit.WebView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

/** Tracks the IME edge while Capacitor lays out the WebView at its destination. */
final class KeyboardDismissAnimation extends WindowInsetsAnimationCompat.Callback {
    private final View decor;
    private final WebView webView;
    private final int[] location = new int[2];
    private WindowInsetsAnimationCompat dismissal;

    static void install(View decor, WebView webView) {
        if (Build.VERSION.SDK_INT >= 30) {
            ViewCompat.setWindowInsetsAnimationCallback(decor, new KeyboardDismissAnimation(decor, webView));
        }
    }

    private KeyboardDismissAnimation(View decor, WebView webView) {
        super(DISPATCH_MODE_CONTINUE_ON_SUBTREE);
        this.decor = decor;
        this.webView = webView;
    }

    @Override
    public WindowInsetsAnimationCompat.BoundsCompat onStart(
        WindowInsetsAnimationCompat animation,
        WindowInsetsAnimationCompat.BoundsCompat bounds
    ) {
        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
            WindowInsetsCompat target = ViewCompat.getRootWindowInsets(decor);
            if (target != null && !target.isVisible(WindowInsetsCompat.Type.ime())) {
                dismissal = animation;
                publishKeyboardEdge(bounds.getUpperBound().bottom);
            }
        }
        return bounds;
    }

    @Override
    public WindowInsetsCompat onProgress(
        WindowInsetsCompat insets,
        List<WindowInsetsAnimationCompat> runningAnimations
    ) {
        if (dismissal != null && runningAnimations.contains(dismissal)) {
            publishKeyboardEdge(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
        }
        return insets;
    }

    @Override
    public void onEnd(WindowInsetsAnimationCompat animation) {
        if (dismissal != animation) return;
        dismissal = null;
        publish("null");
    }

    private void publishKeyboardEdge(int imeBottom) {
        decor.getLocationInWindow(location);
        int keyboardTop = location[1] + decor.getHeight() - imeBottom;
        webView.getLocationInWindow(location);
        int height = Math.max(0, keyboardTop - location[1]);
        publish(height + " / window.devicePixelRatio");
    }

    private void publish(String height) {
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('hasht:keyboard-progress',"
                + "{detail:{height:" + height + "}}));",
            null
        );
    }
}
