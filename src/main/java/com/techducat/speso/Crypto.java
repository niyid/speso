package com.techducat.speso;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.HexFormat;

/** All cryptography in one place. Nothing exotic: SHA-256 and ECDSA (P-256). */
final class Crypto {
    private Crypto() {}

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static String b64(byte[] b) { return Base64.getEncoder().withoutPadding().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }

    static String sign(PrivateKey k, String data) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(k);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return b64(s.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static boolean verify(String pubB64, String data, String sigB64) {
        try {
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(pubB64)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return s.verify(unb64(sigB64));
        } catch (Exception e) { return false; } // malformed anything == invalid
    }

    /** An address is the first 40 hex chars of SHA-256(public key). */
    static String address(String pubB64) { return sha256(pubB64).substring(0, 40); }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static boolean isAddress(String a) { return a != null && a.matches("[0-9a-f]{40}"); }
}
