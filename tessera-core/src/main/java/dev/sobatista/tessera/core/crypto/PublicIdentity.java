package dev.sobatista.tessera.core.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/**
 * The public half of a member's {@link Identity}: what the directory server stores and hands out.
 *
 * <p><b>Trust caveat, stated plainly:</b> a member learns other members' public keys <em>from the server</em>. A
 * malicious server could hand out its own key instead and read the next album key wrapped for it. Tessera makes that
 * detectable rather than impossible: {@link #fingerprint()} is a short hash members can compare out of band (in
 * person, over a call), the same idea as Signal's safety numbers. See docs/SPEC.md, "Threat model".
 */
public record PublicIdentity(String userId, PublicKey encryptionKey, PublicKey signingKey) {

  /** Encrypts ("wraps") an album key so only this member can recover it. */
  public byte[] wrap(SecretKey albumKey) {
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, Identity.oaep());
      return cipher.doFinal(albumKey.getEncoded());
    } catch (GeneralSecurityException e) {
      throw new CryptoException("cannot wrap the album key for " + userId, e);
    }
  }

  public boolean verify(byte[] message, byte[] signatureBytes) {
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(signingKey);
      signature.update(message);
      return signature.verify(signatureBytes);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** A short, human-comparable hash of both public keys and the user id. */
  public String fingerprint() {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update(userId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      sha.update(encryptionKey.getEncoded());
      sha.update(signingKey.getEncoded());
      String hex = HexFormat.of().formatHex(sha.digest(), 0, 10);
      return hex.replaceAll("(.{4})(?!$)", "$1 ");
    } catch (GeneralSecurityException e) {
      throw new CryptoException("SHA-256 unavailable", e);
    }
  }

  /** X.509 encodings, for the wire. */
  public byte[] encodedEncryptionKey() {
    return encryptionKey.getEncoded();
  }

  public byte[] encodedSigningKey() {
    return signingKey.getEncoded();
  }

  public static PublicIdentity decode(String userId, byte[] encryptionKey, byte[] signingKey) {
    try {
      return new PublicIdentity(
          userId,
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encryptionKey)),
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(signingKey)));
    } catch (GeneralSecurityException e) {
      throw new CryptoException("malformed public key for " + userId, e);
    }
  }
}
