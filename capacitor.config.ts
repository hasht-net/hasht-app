import type { CapacitorConfig } from '@capacitor/cli';

// No static `server.url`: the instance is chosen at runtime by the user and
// applied natively by the ServerConfig plugin. The bundled assets are only the
// bootstrap picker — once an instance is set, the webview points at it.
const config: CapacitorConfig = {
  appId: 'net.hasht.app',
  appName: 'Hasht',
  webDir: 'dist',
  server: {
    // The instance is arbitrary and user-supplied, so no fixed allowlist can
    // describe it. Without this Capacitor treats the configured server as
    // "external" and punts it to Safari instead of loading it in-app.
    // TODO: narrow to the active instance's host via a navigation policy in
    // MainViewController, so stray links still open in the system browser.
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
    Keyboard: {
      // The frontend declares interactive-widget=resizes-content and owns
      // inset handling in useViewportKeyboard; letting the plugin resize the
      // webview too makes the composer jump on focus.
      resize: 'none',
    },
  },
};

export default config;
