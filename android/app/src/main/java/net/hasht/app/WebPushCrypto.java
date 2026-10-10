package net.hasht.app;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 8291 (Web Push message encryption) on the receiving end: what the
 * backend encrypts with `web-push`, the device opens here. Plain JCA, no
 * Android APIs, so it runs in JVM unit tests too.
 */
final class WebPushCrypto {
    private WebPushCrypto() {}

    static final ECParameterSpec P256 = p256();

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static PrivateKey privateKey(byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    /** Uncompressed X9.62 point: 0x04 || X || Y — what `p256dh` carries. */
    static byte[] encodePublic(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECPublicKey decodePublic(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 4) throw new GeneralSecurityException("bad point");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC")
            .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    private static void copyFixed(BigInteger value, byte[] out, int offset) {
        byte[] raw = value.toByteArray();
        int start = Math.max(0, raw.length - 32);
        int length = raw.length - start;
        System.arraycopy(raw, start, out, offset + 32 - length, length);
    }

    /**
     * Decrypts one aes128gcm body for the receiver whose keys are given.
     * Throws on the wrong key — the GCM tag won't verify — which is how the
     * device tells which instance a push is from.
     */
    static byte[] decrypt(byte[] body, PrivateKey receiverPrivate, byte[] receiverPublic, byte[] auth)
        throws GeneralSecurityException {
        if (body.length < 21) throw new GeneralSecurityException("short body");
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int rs = ((body[16] & 0xff) << 24) | ((body[17] & 0xff) << 16) | ((body[18] & 0xff) << 8) | (body[19] & 0xff);
        int idlen = body[20] & 0xff;
        if (idlen != 65 || body.length < 21 + idlen + 16) throw new GeneralSecurityException("bad header");
        byte[] senderPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idlen, body.length);
        // Backends send a single record; more would need a nonce per record.
        if (ciphertext.length > rs) throw new GeneralSecurityException("multiple records");

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(receiverPrivate);
        agreement.doPhase(decodePublic(senderPublic), true);
        byte[] ecdh = agreement.generateSecret();

        byte[] keyInfo = concat("WebPush: info\0".getBytes(), receiverPublic, senderPublic);
        byte[] ikm = hkdf(auth, ecdh, keyInfo, 32);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);

        // Zero padding after a 0x02 delimiter (last record).
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        if (end < 0 || padded[end] != 2) throw new GeneralSecurityException("bad padding");
        return Arrays.copyOf(padded, end);
    }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        // One HMAC block covers every length used here (≤ 32).
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }
}
