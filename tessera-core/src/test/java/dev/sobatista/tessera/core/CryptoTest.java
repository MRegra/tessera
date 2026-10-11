package dev.sobatista.tessera.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sobatista.tessera.core.crypto.Aead;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.crypto.Identity;
import dev.sobatista.tessera.core.crypto.PublicIdentity;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class CryptoTest {

  private static final Identity ALICE = Identity.generate("alice");
  private static final Identity BOB = Identity.generate("bob");
  private static final byte[] AAD = "context".getBytes(StandardCharsets.UTF_8);

  @Test
  void aeadRoundTrips() {
    SecretKey key = Aead.newKey();
    byte[] message = "a photo".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(message, Aead.decrypt(key, Aead.encrypt(key, message, AAD), AAD));
  }

  @Test
  void aeadUsesAFreshNonceEveryTime() {
    SecretKey key = Aead.newKey();
    byte[] message = "same input".getBytes(StandardCharsets.UTF_8);
    assertFalse(java.util.Arrays.equals(Aead.encrypt(key, message, AAD), Aead.encrypt(key, message, AAD)));
  }

  @Test
  void aeadDetectsAFlippedBit() {
    SecretKey key = Aead.newKey();
    byte[] sealed = Aead.encrypt(key, "a photo".getBytes(StandardCharsets.UTF_8), AAD);
    sealed[sealed.length - 1] ^= 1;
    assertThrows(CryptoException.class, () -> Aead.decrypt(key, sealed, AAD));
  }

  @Test
  void aeadRejectsTheWrongContext() {
    SecretKey key = Aead.newKey();
    byte[] sealed = Aead.encrypt(key, "a photo".getBytes(StandardCharsets.UTF_8), AAD);
    assertThrows(CryptoException.class,
        () -> Aead.decrypt(key, sealed, "another-context".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aeadRejectsTheWrongKey() {
    byte[] sealed = Aead.encrypt(Aead.newKey(), "a photo".getBytes(StandardCharsets.UTF_8), AAD);
    assertThrows(CryptoException.class, () -> Aead.decrypt(Aead.newKey(), sealed, AAD));
  }

  @Test
  void onlyTheRecipientCanUnwrapAnAlbumKey() {
    SecretKey albumKey = Aead.newKey();
    byte[] wrapped = BOB.publicIdentity().wrap(albumKey);
    assertArrayEquals(albumKey.getEncoded(), BOB.unwrap(wrapped).getEncoded());
    assertThrows(CryptoException.class, () -> ALICE.unwrap(wrapped));
  }

  @Test
  void signaturesBindSignerAndMessage() {
    byte[] message = "catalog".getBytes(StandardCharsets.UTF_8);
    byte[] signature = ALICE.sign(message);
    assertTrue(ALICE.publicIdentity().verify(message, signature));
    assertFalse(BOB.publicIdentity().verify(message, signature));
    assertFalse(ALICE.publicIdentity().verify("catalog!".getBytes(StandardCharsets.UTF_8), signature));
  }

  @Test
  void publicIdentitySurvivesTheWireAndKeepsItsFingerprint() {
    PublicIdentity original = ALICE.publicIdentity();
    PublicIdentity decoded = PublicIdentity.decode("alice", original.encodedEncryptionKey(),
        original.encodedSigningKey());
    assertEquals(original.fingerprint(), decoded.fingerprint());
    assertNotEquals(original.fingerprint(), BOB.publicIdentity().fingerprint());
  }
}
