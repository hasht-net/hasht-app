import type { CapacitorConfig } from '@capacitor/cli';

// No static `server.url`: the instance is chosen at runtime by the user and
// applied natively by the ServerConfig plugin. The bundled assets are only the
// bootstrap picker — once an instance is set, the webview points at it.
const config: CapacitorConfig = {
  appId: 'net.hasht.app',
  appName: 'Hasht',
  webDir: 'dist',
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
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert'],
    },
    Keyboard: {
      resize: 'none', // the frontend's useViewportKeyboard owns inset handling
    },
  },
};

export default config;
