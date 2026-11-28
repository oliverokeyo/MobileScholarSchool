package com.example.mobilescholarschool.Security;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * NEW: turns a password into a "verifier" and later checks a typed password against it.
 *
 * LESSON 1: Why not just SHA-256 the password? SHA-256 is FAST, so an attacker who copies the
 * database can try billions of guesses per second. PBKDF2 repeats the hash hundreds of
 * thousands of times on purpose, making each guess slow for the attacker (and barely
 * noticeable, about a fifth of a second, for one student).
 *
 * LESSON 2: The SALT is random bytes made fresh for each student and stored next to the
 * verifier. It makes two students with the same password produce different verifiers, and
 * it defeats pre-computed "rainbow tables". It is NOT secret.
 *
 * LESSON 3: The PEPPER. After PBKDF2 we run the result through HMAC-SHA256 using a key created
 * inside the Android Keystore. That key never leaves the secure hardware, so a copy of the
 * database file (a backup, a rooted phone's file dump) is useless on any other device:
 * the attacker cannot reproduce the last step. This is "defence in depth".
 *
 * FAIL CLOSED: if the Keystore is unavailable we throw, and offline sign-in is simply not
 * enabled. We never fall back to a weaker scheme.
 *
 * Passwords arrive as char[] (not String) so the caller can overwrite them with zeros as soon
 * as we are done. A String cannot be wiped from memory in Java.
 */
public final class PasswordHasher {

    /** Thrown when the Keystore key is gone (backup restored on a new phone, key invalidated). */
    public static final class KeyUnavailableException extends GeneralSecurityException {
        public KeyUnavailableException(String message) {
            super(message);
        }
    }

    /** Everything that must be stored to verify a password later. */
    public static final class Hash {
        public final byte[] salt;
        public final byte[] verifier;
        public final int iterations;
        public final String algorithm;

        Hash(byte[] salt, byte[] verifier, int iterations, String algorithm) {
            this.salt = salt;
            this.verifier = verifier;
            this.iterations = iterations;
            this.algorithm = algorithm;
        }
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "scholar_offline_auth_pepper_v1";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    // Stored per row, so this number can be raised later without breaking existing students.
    private static final int ITERATIONS = 210_000;

    private PasswordHasher() { }

    /** Makes a brand new salt + verifier for this password. Slow on purpose (~0.1-0.5 s). */
    public static Hash create(char[] password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        String algorithm = preferredAlgorithm();
        byte[] verifier = derive(password, salt, ITERATIONS, algorithm, true);
        return new Hash(salt, verifier, ITERATIONS, algorithm);
    }

    /**
     * Does this password produce the stored verifier?
     * @throws KeyUnavailableException if the Keystore key no longer exists
     */
    public static boolean verify(char[] password, byte[] salt, int iterations, String algorithm,
                                 byte[] expected) throws GeneralSecurityException {
        byte[] actual = derive(password, salt, iterations, algorithm, false);
        try {
            // LESSON 4: MessageDigest.isEqual takes the SAME time whether the first byte or the
            // last byte differs. A normal equals() stops at the first difference, and that tiny
            // timing difference can leak the answer one byte at a time ("timing attack").
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static String preferredAlgorithm() {
        // HmacSHA256 PBKDF2 exists from Android 8.0 (API 26); older phones only have SHA-1.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, String algorithm,
                                 boolean createKeyIfMissing) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        byte[] stretched = null;
        try {
            stretched = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
            return pepper(stretched, createKeyIfMissing);
        } finally {
            spec.clearPassword();
            if (stretched != null) {
                Arrays.fill(stretched, (byte) 0);
            }
        }
    }

    private static byte[] pepper(byte[] data, boolean createKeyIfMissing)
            throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Hardware-backed HMAC keys need Android 6.0. Below that we refuse (fail closed).
            throw new GeneralSecurityException("Offline sign-in needs Android 6.0 or newer");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keystoreKey(createKeyIfMissing));
            return mac.doFinal(data);
        } catch (InvalidKeyException e) {
            // Includes KeyPermanentlyInvalidatedException: the key can no longer be used
            throw new KeyUnavailableException("The device key was invalidated");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private static SecretKey keystoreKey(boolean createIfMissing) throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            Key existing = store.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                return (SecretKey) existing;
            }
            if (!createIfMissing) {
                throw new KeyUnavailableException("The device key is missing");
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .build());
            return generator.generateKey();
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }
}
