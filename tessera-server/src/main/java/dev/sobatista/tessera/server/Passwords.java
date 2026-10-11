package dev.sobatista.tessera.server;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password storage: PBKDF2-HMAC-SHA256 with a per-user random salt.
 *
 * <p>The original course project stored passwords as plain strings and compared them with {@code equals}. Two things
 * are wrong with that: a leaked database reveals every password (which people reuse elsewhere), and {@code equals}
 * returns faster the earlier the strings differ, which leaks information through timing. Here the database only ever
 * holds a slow, salted hash, and comparison takes the same time whatever the input.
 *
 * <p>600,000 iterations is OWASP's 2023 recommendation for PBKDF2-SHA256. Argon2id would be better still, but needs a
 * dependency; PBKDF2 ships with the JDK, which matters for a codebase meant to be read.
 */
final class Passwords {

  static final int DEFAULT_ITERATIONS = 600_000;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final int iterations;

  Passwords(int iterations) {
    this.iterations = iterations;
  }

  record Hash(byte[] salt, int iterations, byte[] value) {}

  Hash hash(char[] password) {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    return new Hash(salt, iterations, derive(password, salt, iterations));
  }

  boolean matches(char[] password, Hash stored) {
    return MessageDigest.isEqual(derive(password, stored.salt(), stored.iterations()), stored.value());
  }

  /** Spends the same effort as a real check, so "no such user" isn't faster than "wrong password". */
  void burnTime(char[] password) {
    derive(password, new byte[SALT_BYTES], iterations);
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PBKDF2 unavailable", e);
    } finally {
      spec.clearPassword();
    }
  }

  static void wipe(char[] password) {
    Arrays.fill(password, '\0');
  }
}
