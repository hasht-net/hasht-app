package net.hasht.app;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ServerConfigPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onBackPressed() {
        PluginHandle handle = getBridge().getPlugin("ServerConfig");
        if (handle != null
            && ((ServerConfigPlugin) handle.getInstance()).returnToWizardIfRemote()) {
            return;
        }
        super.onBackPressed();
    }
}
