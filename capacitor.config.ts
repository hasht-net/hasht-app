import type { CapacitorConfig } from '@capacitor/cli';

// No static `server.url`: the instance is chosen at runtime by the user and
// applied natively by the ServerConfig plugin. The bundled assets are only the
// bootstrap picker — once an instance is set, the webview points at it.
const config: CapacitorConfig = {
  appId: 'net.hasht.app',
  appName: 'Hasht',
  webDir: 'dist',
  backgroundColor: '#151517',
  server: {
    // The instance is arbitrary and user-supplied, so no fixed allowlist can
    // describe it. Without this Capacitor treats the configured server as
    // "external" and punts it to Safari instead of loading it in-app.
    // Links that leave the instance are caught natively and opened outside the
    // webview instead (LinkOpener on both platforms).
    allowNavigation: ['*'],
  },
  ios: {
    // Must stay false. App-Bound Domains requires a fixed <=10 domain list in
    // Info.plist, which a self-hosted client can never satisfy.
    limitsNavigationsToAppBoundDomains: false,
    contentInset: 'never',
  },
  android: {
    allowMixedContent: false,
  },
  plugins: {
    App: {
      // MainActivity owns Android back. Left on, this handler swallows back
      // whenever the webview has no history.
      disableBackButtonHandler: true,
    },
    Keyboard: {
      // The frontend declares interactive-widget=resizes-content and owns
      // inset handling in useViewportKeyboard; letting the plugin resize the
      // webview too makes the composer jump on focus.
      resize: 'none',
    },
  },
};

export default config;
