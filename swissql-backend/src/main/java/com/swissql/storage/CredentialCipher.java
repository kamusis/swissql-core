package com.swissql.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * AES-256-GCM encryption for credential passwords at rest.
 *
 * <p>Key resolution order:
 * <ol>
 *   <li>{@code SWISSQL_CREDENTIAL_KEY} env var (base64-encoded 32 bytes, or any string SHA-256-hashed to 32 bytes)</li>
 *   <li>Auto-generated key persisted to {@code {dataDir}/.credential_key} (owner-read-only)</li>
 * </ol>
 */
public class CredentialCipher {
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;

    public CredentialCipher(SecretKey key) {
        this.key = key;
    }

    /**
     * Build a cipher from {@code SWISSQL_CREDENTIAL_KEY}, falling back to an auto-generated keyfile.
     * Logs a warning when falling back so operators notice.
     */
    public static CredentialCipher fromEnvironment(Path dataDir) {
        String envKey = System.getenv("SWISSQL_CREDENTIAL_KEY");
        if (envKey != null && !envKey.isBlank()) {
            byte[] keyBytes = deriveKeyBytes(envKey.strip());
            return new CredentialCipher(new SecretKeySpec(keyBytes, "AES"));
        }

        Path keyFile = dataDir.resolve(".credential_key");
        log.warn(
                "SWISSQL_CREDENTIAL_KEY is not set. Using auto-generated key stored at {}. " +
                "Set the env var to make credentials.json portable across restarts and instances.",
                keyFile);
        return new CredentialCipher(loadOrGenerateKey(dataDir, keyFile));
    }

    /**
     * Encrypt a plaintext password. Returns a Base64-encoded blob of {@code iv || ciphertext+tag}.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[GCM_IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    /**
     * Decrypt a blob produced by {@link #encrypt}. Returns the original plaintext password.
     */
    public String decrypt(String blob) {
        try {
            byte[] combined = Base64.getDecoder().decode(blob);
            if (combined.length <= GCM_IV_BYTES) {
                throw new IllegalArgumentException("Encrypted blob too short");
            }

            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(combined, GCM_IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    // -- private helpers --

    /**
     * If {@code value} is valid Base64 and decodes to exactly 32 bytes, use it directly.
     * Otherwise SHA-256-hash it to produce a stable 32-byte key.
     */
    private static byte[] deriveKeyBytes(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // not base64 — fall through
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static SecretKey loadOrGenerateKey(Path dataDir, Path keyFile) {
        if (Files.exists(keyFile)) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(Files.readString(keyFile).strip());
                return new SecretKeySpec(keyBytes, "AES");
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Failed to read credential keyfile: " + keyFile, e);
            }
        }

        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            SecretKey newKey = kg.generateKey();

            Files.createDirectories(dataDir);
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(newKey.getEncoded()));
            restrictToOwner(keyFile);

            return newKey;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Failed to generate/store credential key", e);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem (Windows) — skip silently
        }
    }
}
