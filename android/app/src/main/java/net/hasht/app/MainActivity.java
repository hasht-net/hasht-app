package net.hasht.app;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Before super: BridgeActivity builds the bridge from `config` during
        // onCreate, and the active server has to be in it by then for Capacitor
        // to treat that origin as the app and inject the bridge into it.
        config = ServerConfigPlugin.runtimeConfig(this);
        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(CallSessionPlugin.class);
        registerPlugin(NativeAuthPlugin.class);
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
