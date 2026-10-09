package net.hasht.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public class WebPushCryptoTest {
    private static byte[] b64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    // RFC 8291 Appendix A.
    private static final byte[] UA_PRIVATE = b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94");
    private static final byte[] UA_PUBLIC = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
    private static final byte[] AUTH = b64("BTBZMqHH6r4Tts7J_aSIgg");
    private static final byte[] MESSAGE = b64(
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");

    private static PrivateKey vectorPrivate() throws GeneralSecurityException {
        return KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, UA_PRIVATE), WebPushCrypto.P256));
    }

    @Test
    public void decryptsTheRfcTestVector() throws Exception {
        byte[] plain = WebPushCrypto.decrypt(MESSAGE, vectorPrivate(), UA_PUBLIC, AUTH);
        assertEquals("When I grow up, I want to be a watermelon", new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    public void publicKeyRoundTripsThroughX962() throws Exception {
        assertArrayEquals(UA_PUBLIC, WebPushCrypto.encodePublic(WebPushCrypto.decodePublic(UA_PUBLIC)));
    }

    @Test
    public void theWrongKeyFails() throws Exception {
        KeyPair other = WebPushCrypto.generate();
        try {
            WebPushCrypto.decrypt(MESSAGE, other.getPrivate(),
                WebPushCrypto.encodePublic((ECPublicKey) other.getPublic()), AUTH);
            fail("decrypted with the wrong key");
        } catch (GeneralSecurityException expected) {
            // The GCM tag is what identifies the instance.
        }
    }

    @Test
    public void roundTripsWithAGeneratedKey() throws Exception {
        KeyPair receiver = WebPushCrypto.generate();
        byte[] receiverPublic = WebPushCrypto.encodePublic((ECPublicKey) receiver.getPublic());
        byte[] pkcs8 = receiver.getPrivate().getEncoded();
        byte[] body = encrypt("{\"title\":\"hi\"}".getBytes(StandardCharsets.UTF_8), receiverPublic, AUTH);

        byte[] plain = WebPushCrypto.decrypt(body, WebPushCrypto.privateKey(pkcs8), receiverPublic, AUTH);
        assertEquals("{\"title\":\"hi\"}", new String(plain, StandardCharsets.UTF_8));
    }

    /** The sender's side, as the backend's `web-push` does it. */
    private static byte[] encrypt(byte[] plain, byte[] receiverPublic, byte[] auth) throws Exception {
        KeyPair sender = WebPushCrypto.generate();
        byte[] senderPublic = WebPushCrypto.encodePublic((ECPublicKey) sender.getPublic());
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(sender.getPrivate());
        agreement.doPhase(WebPushCrypto.decodePublic(receiverPublic), true);
        byte[] ikm = WebPushCrypto.hkdf(auth, agreement.generateSecret(),
            WebPushCrypto.concat("WebPush: info\0".getBytes(), receiverPublic, senderPublic), 32);
        byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(), 16);
        byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] sealed = cipher.doFinal(WebPushCrypto.concat(plain, new byte[] {2}));
        return WebPushCrypto.concat(salt, new byte[] {0, 0, 16, 0, 65}, senderPublic, sealed);
    }
}
