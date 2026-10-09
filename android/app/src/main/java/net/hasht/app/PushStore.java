package net.hasht.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The device's push registrations, one per instance, each with its own
 * keypair. Stored as one blob encrypted under an Android Keystore AES key: the
 * ECDH keys have to be exportable software keys (Keystore EC keys can't do
 * ECDH on every supported API level), so the Keystore wraps them instead.
 */
final class PushStore {
    private PushStore() {}

    private static final String PREFS = "net.hasht.app.push";
    private static final String KEY_BLOB = "registrations";
    private static final String KEY_TOKEN = "fcmToken";
    private static final String WRAP_ALIAS = "net.hasht.app.push.wrap";

    static final class Registration {
        final String origin;
        final String relayUrl;
        final String id;
        final String endpoint;
        final byte[] privateKey; // PKCS#8
        final byte[] publicKey; // X9.62 uncompressed
        final byte[] auth;

        Registration(String origin, String relayUrl, String id, String endpoint,
                     byte[] privateKey, byte[] publicKey, byte[] auth) {
            this.origin = origin;
            this.relayUrl = relayUrl;
            this.id = id;
            this.endpoint = endpoint;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.auth = auth;
        }

        PrivateKey key() throws GeneralSecurityException {
            return WebPushCrypto.privateKey(privateKey);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("origin", origin)
                .put("relayUrl", relayUrl)
                .put("id", id)
                .put("endpoint", endpoint)
                .put("privateKey", b64(privateKey))
                .put("publicKey", b64(publicKey))
                .put("auth", b64(auth));
        }

        static Registration fromJson(JSONObject o) throws JSONException {
            return new Registration(
                o.getString("origin"), o.getString("relayUrl"), o.getString("id"),
                o.getString("endpoint"), unb64(o.getString("privateKey")),
                unb64(o.getString("publicKey")), unb64(o.getString("auth")));
        }
    }

    static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static byte[] unb64(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized List<Registration> load(Context context) {
        List<Registration> out = new ArrayList<>();
        String blob = prefs(context).getString(KEY_BLOB, null);
        if (blob == null) return out;
        try {
            byte[] raw = unb64(blob);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
            byte[] json = cipher.doFinal(raw, 12, raw.length - 12);
            JSONArray all = new JSONArray(new String(json, StandardCharsets.UTF_8));
            for (int i = 0; i < all.length(); i++) out.add(Registration.fromJson(all.getJSONObject(i)));
        } catch (GeneralSecurityException | JSONException | IllegalArgumentException e) {
            // Unreadable (e.g. the Keystore key went with a backup restore):
            // the page registers again and the relay prunes the orphans.
            android.util.Log.w("NativePush", "dropping unreadable registrations", e);
        }
        return out;
    }

    @android.annotation.SuppressLint("ApplySharedPref") // see the commit() below
    static synchronized void save(Context context, List<Registration> regs) {
        if (regs.isEmpty()) {
            prefs(context).edit().remove(KEY_BLOB).apply();
            return;
        }
        try {
            JSONArray all = new JSONArray();
            for (Registration r : regs) all.put(r.toJson());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] sealed = cipher.doFinal(all.toString().getBytes(StandardCharsets.UTF_8));
            // commit, not apply: the caller hands the keys to the page next,
            // and a lost write would leave the instance unable to reach us.
            if (!prefs(context).edit().putString(KEY_BLOB, b64(WebPushCrypto.concat(cipher.getIV(), sealed))).commit()) {
                throw new IllegalStateException("could not store push registrations");
            }
        } catch (GeneralSecurityException | JSONException e) {
            throw new IllegalStateException("could not store push registrations", e);
        }
    }

    static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, null);
    }

    static void setToken(Context context, String token) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply();
    }

    private static SecretKey wrapKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyStore.Entry entry = keyStore.getEntry(WRAP_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        // No user authentication: pushes are decrypted while the phone is locked.
        generator.init(new KeyGenParameterSpec.Builder(
            WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
