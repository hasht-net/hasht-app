package net.hasht.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.browser.customtabs.CustomTabsIntent;

/**
 * Where links that leave the instance open.
 *
 * Loading them in the main webview would replace the chat UI with an arbitrary
 * site that has no way back, so they are handed off instead. The default is an
 * in-app Custom Tab (the user stays in the app, back returns to the chat, and
 * the browser's own cookies and password manager apply); "system" sends them to
 * the default browser app. The frontend exposes the choice through
 * ServerConfig.setLinkMode.
 */
final class LinkOpener {
    static final String IN_APP = "in-app";
    static final String SYSTEM = "system";

    private static final String KEY_MODE = "linkMode";

    private LinkOpener() {}

    static String mode(Context context) {
        String stored = ServerConfigPlugin.prefs(context).getString(KEY_MODE, IN_APP);
        return SYSTEM.equals(stored) ? SYSTEM : IN_APP;
    }

    static void setMode(Context context, String mode) {
        ServerConfigPlugin.prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    static boolean isWeb(Uri url) {
        String scheme = url.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** A web URL on a different origin from the instance the webview is showing. */
    static boolean leavesApp(Uri url, Uri app) {
        return isWeb(url)
            && !ServerConfigPlugin.originKey(url).equals(ServerConfigPlugin.originKey(app));
    }

    static void open(Activity activity, Uri url) {
        try {
            if (isWeb(url) && IN_APP.equals(mode(activity))) {
                new CustomTabsIntent.Builder().build().launchUrl(activity, url);
            } else {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
            }
        } catch (ActivityNotFoundException e) {
            // No browser (or handler) installed: nothing sensible to fall back to.
        }
    }
}
