package dev.sobatista.tessera.core.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * A member's private identity: two key pairs, one per job.
 *
 * <ul>
 *   <li>An <b>RSA-3072</b> pair for <em>receiving</em> album keys. Whoever shares an album with you encrypts the album
 *       key with your public key ("wrapping"); only your private key can unwrap it. Padding is OAEP with SHA-256 —
 *       never the old PKCS#1 v1.5 padding, which has a long history of practical attacks.</li>
 *   <li>An <b>Ed25519</b> pair for <em>signing</em> your catalog, so the other members can tell it really is yours and
 *       that nobody — including the server or whoever hosts your storage — changed it.</li>
 * </ul>
 *
 * <p>Using separate keys for encryption and signing is deliberate: one key, one purpose.
 *
 * <p>The private halves never leave the member's device. The server only ever learns {@link #publicIdentity()}.
 */
public final class Identity {

  private static final OAEPParameterSpec OAEP =
      new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  private final String userId;
  private final KeyPair encryption;
  private final KeyPair signing;

  private Identity(String userId, KeyPair encryption, KeyPair signing) {
    this.userId = userId;
    this.encryption = encryption;
    this.signing = signing;
  }

  public static Identity generate(String userId) {
    try {
      KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
      rsa.initialize(3072);
      KeyPairGenerator ed = KeyPairGenerator.getInstance("Ed25519");
      return new Identity(userId, rsa.generateKeyPair(), ed.generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new CryptoException("cannot generate member keys", e);
    }
  }

  public String userId() {
    return userId;
  }

  public PublicIdentity publicIdentity() {
    return new PublicIdentity(userId, encryption.getPublic(), signing.getPublic());
  }

  /** Recovers an album key that someone wrapped for us with {@link PublicIdentity#wrap(SecretKey)}. */
  public SecretKey unwrap(byte[] wrappedKey) {
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(Cipher.DECRYPT_MODE, encryption.getPrivate(), OAEP);
      return Aead.keyFromBytes(cipher.doFinal(wrappedKey));
    } catch (GeneralSecurityException e) {
      throw new CryptoException("cannot unwrap the album key: it was not wrapped for " + userId, e);
    }
  }

  public byte[] sign(byte[] message) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(signingKey());
      signature.update(message);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new CryptoException("signing failed", e);
    }
  }

  private PrivateKey signingKey() {
    return signing.getPrivate();
  }

  static OAEPParameterSpec oaep() {
    return OAEP;
  }
}
