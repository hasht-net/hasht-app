package net.hasht.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Launchers start shortcut intents with CLEAR_TASK, which would rebuild the
 * webview and drop a live call. This forwards into the running task instead,
 * where MainActivity (singleTask) gets it as a new intent.
 */
public class ShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent forward = new Intent(getIntent()).setClass(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(forward);
        finish();
    }
}
