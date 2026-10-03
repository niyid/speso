package com.techducat.speso;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/**
 * A keypair in a file.
 *
 * With a password the private key is encrypted: PBKDF2-HMAC-SHA256 (200k iterations, random
 * salt) derives an AES-256 key, and AES-GCM encrypts the key with the public key as
 * authenticated data. A wrong password or any tampering makes decryption fail.
 * With a null password the key is stored in the clear (used only for node identity keys,
 * which hold no funds, and for tests).
 *
 * Losing the file or the password loses the spesmiloj. There is no recovery.
 */
final class Wallet {
    private static final String ENC = "SPESO-WALLET-ENC-1", PLAIN = "SPESO-WALLET-PLAIN-1";
    private static final int ITER = Integer.getInteger("speso.pbkdf2", 200_000);

    final PrivateKey priv;
    final String pubB64;
    final String address;

    private Wallet(PrivateKey priv, String pubB64) {
        this.priv = priv; this.pubB64 = pubB64; this.address = Crypto.address(pubB64);
    }

    // ---------------------------------------------------------------- file handling

    static Wallet loadOrCreate(String file, char[] password) throws IOException, GeneralSecurityException {
        Path p = Paths.get(file).toAbsolutePath();
        if (Files.exists(p)) return load(p, password);
        Files.createDirectories(p.getParent());

        KeyPair kp = Crypto.newKeyPair();
        String pub = Crypto.b64(kp.getPublic().getEncoded());
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        List<String> lines;
        if (password == null) {
            lines = List.of(PLAIN, pub, Crypto.b64(pkcs8));
        } else {
            byte[] salt = new byte[16], iv = new byte[12];
            SecureRandom rnd = new SecureRandom();
            rnd.nextBytes(salt); rnd.nextBytes(iv);
            Cipher c = cipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITER), iv, pub);
            lines = List.of(ENC, String.valueOf(ITER), Crypto.b64(salt), Crypto.b64(iv), pub, Crypto.b64(c.doFinal(pkcs8)));
        }
        // Create the file already private (0600). Setting permissions after writing would leave a window
        // in which the key file is readable by others under a permissive umask.
        try {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {                 // non-POSIX filesystem
            Files.createFile(p);
        }
        Files.write(p, lines, StandardCharsets.UTF_8);
        return new Wallet(toPrivate(pkcs8), pub);
    }

    private static Wallet load(Path p, char[] password) throws IOException, GeneralSecurityException {
        List<String> l = Files.readAllLines(p, StandardCharsets.UTF_8);
        if (l.isEmpty()) throw new GeneralSecurityException("empty wallet file");
        switch (l.get(0)) {
            case PLAIN -> { return new Wallet(toPrivate(Crypto.unb64(l.get(2))), l.get(1)); }
            case ENC -> {
                if (password == null) throw new GeneralSecurityException("wallet is encrypted: password required");
                int iter = Integer.parseInt(l.get(1));
                byte[] salt = Crypto.unb64(l.get(2)), iv = Crypto.unb64(l.get(3));
                String pub = l.get(4);
                try {
                    byte[] pkcs8 = cipher(Cipher.DECRYPT_MODE, deriveKey(password, salt, iter), iv, pub)
                            .doFinal(Crypto.unb64(l.get(5)));
                    return new Wallet(toPrivate(pkcs8), pub);
                } catch (AEADBadTagException e) {
                    throw new GeneralSecurityException("wrong password or corrupted wallet");
                }
            }
            default -> throw new GeneralSecurityException("unknown wallet format");
        }
    }

    private static SecretKey deriveKey(char[] pw, byte[] salt, int iter) throws GeneralSecurityException {
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw, salt, iter, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv, String aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, iv));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c;
    }

    private static PrivateKey toPrivate(byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    // ---------------------------------------------------------------- building transactions

    private Transaction sign(char kind, String to, long amount, long aux, long fee, long seq, String data) {
        Transaction t = new Transaction(pubB64, kind, to, amount, aux, fee, seq, data, null);
        return t.withSig(Crypto.sign(priv, t.signingText()));
    }

    /** Pay `amount` spesoj. */
    Transaction pay(String to, long amount, long fee, long seq) {
        return sign(Transaction.PAY, to, amount, 0, fee, seq, "-");
    }

    /** Pay `gbuAmount` thousandths of a GBU, converted to spesoj at the chain's rate, costing at most `maxSpesoj`. */
    Transaction payGbu(String to, long gbuAmount, long maxSpesoj, long fee, long seq) {
        return sign(Transaction.PAY_GBU, to, gbuAmount, maxSpesoj, fee, seq, "-");
    }

    /** Publish my reading of the economy (indicators in hundredths). Weighted by my balance; ratifies or vetoes, never sets. */
    Transaction report(long[] indicators, long fee, long seq) {
        return sign(Transaction.REPORT, "-", 0, 0, fee, seq, EconomyIndex.encode(indicators));
    }

    /** Publisher only: sign the data. Invalid from any address not in Params.PUBLISHERS. Fee may be 0. */
    Transaction attest(long[] indicators, long fee, long seq) {
        return sign(Transaction.ATTEST, "-", 0, 0, fee, seq, EconomyIndex.encode(indicators));
    }

    /** Publisher only: sign the market value of one spesmilo, in score units (10000 == 1 GBU). */
    Transaction quote(long marketScore, long fee, long seq) {
        return sign(Transaction.QUOTE, "-", 0, 0, fee, seq, Long.toString(marketScore));
    }
}
