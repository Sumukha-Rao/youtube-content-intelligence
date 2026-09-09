package com.yci.service.credentials;

import com.yci.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the API keys users paste into Settings before they reach the database.
 *
 * The key comes from {@code APP_SECRET_KEY}. When it is unset the cipher is
 * inactive and values are stored as typed — the app still works, it simply offers
 * no protection if the database is read by someone else, which is logged once at
 * startup. Stored values carry an {@code enc:} prefix when encrypted, so a
 * database written before the secret was configured stays readable afterwards.
 *
 * AES-GCM with a random 96-bit IV per value; the IV is prepended to the
 * ciphertext, so two identical keys never encrypt to the same string.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String PREFIX = "enc:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties props) {
        String secret = props.getSecretKey();
        if (secret == null || secret.isBlank()) {
            this.key = null;
            log.warn("APP_SECRET_KEY is not set — API keys saved in Settings are stored in plain text. "
                     + "Set it to any long random string to encrypt them at rest.");
        } else {
            this.key = deriveKey(secret);
        }
    }

    public boolean isActive() {
        return key != null;
    }

    /** Returns the value as it should be stored. Null and blank pass straight through. */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        if (key == null) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            // Never lose the user's input over an encryption problem.
            log.error("Could not encrypt a stored secret, saving it as plain text: {}", e.getMessage());
            return plain;
        }
    }

    /** Reverses {@link #encrypt}. Values without the prefix are returned unchanged. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        if (key == null) {
            log.warn("A stored secret is encrypted but APP_SECRET_KEY is not set — ignoring it.");
            return null;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong APP_SECRET_KEY, or a corrupted row. Fall back to the server key.
            log.warn("Could not decrypt a stored secret (has APP_SECRET_KEY changed?) — ignoring it.");
            return null;
        }
    }

    private static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive an encryption key from APP_SECRET_KEY", e);
        }
    }
}
