package dev.sobatista.tessera.core.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption: AES-256 in GCM mode.
 *
 * <p>"Authenticated" is the important word. Plain AES (and especially AES in ECB mode, which is what a bare
 * {@code Cipher.getInstance("AES")} gives you) only hides data; anyone can flip bits in the ciphertext and you'll
 * decrypt garbage without noticing. GCM adds a tag, so tampering is detected and decryption fails loudly.
 *
 * <p>The optional <em>associated data</em> (AAD) is authenticated but not encrypted. Tessera uses it to bind a
 * ciphertext to its context (which album, which photo, which key epoch), so a valid ciphertext can't be cut out of
 * one place and pasted into another.
 *
 * <p>Wire format: {@code nonce (12 bytes) || ciphertext-with-tag}. A fresh random nonce per message, never reused
 * with the same key.
 */
public final class Aead {

  public static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Aead() {}

  public static SecretKey newKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(KEY_BYTES * 8, RANDOM);
      return generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("cannot generate an AES key", e);
    }
  }

  public static SecretKey keyFromBytes(byte[] raw) {
    if (raw.length != KEY_BYTES) {
      throw new CryptoException("an album key must be " + KEY_BYTES + " bytes, got " + raw.length);
    }
    return new SecretKeySpec(raw, "AES");
  }

  public static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] associatedData) {
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(associatedData);
      byte[] sealed = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("encryption failed", e);
    }
  }

  /** Decrypts and verifies; throws {@link CryptoException} if the data, the key or the associated data is wrong. */
  public static byte[] decrypt(SecretKey key, byte[] message, byte[] associatedData) {
    if (message.length < NONCE_BYTES + TAG_BITS / 8) {
      throw new CryptoException("ciphertext is too short to be valid");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, message, 0, NONCE_BYTES));
      cipher.updateAAD(associatedData);
      return cipher.doFinal(message, NONCE_BYTES, message.length - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("decryption failed: wrong key, or the data was tampered with", e);
    }
  }
}
