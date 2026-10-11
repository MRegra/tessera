package dev.sobatista.tessera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sobatista.tessera.core.album.AlbumKey;
import dev.sobatista.tessera.core.album.AlbumKeyring;
import dev.sobatista.tessera.core.album.Catalog;
import dev.sobatista.tessera.core.album.PhotoEntry;
import dev.sobatista.tessera.core.album.SealedCatalog;
import dev.sobatista.tessera.core.crypto.Aead;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.crypto.Identity;
import org.junit.jupiter.api.Test;

class SealedCatalogTest {

  private static final Identity ALICE = Identity.generate("alice");
  private static final Identity MALLORY = Identity.generate("alice"); // same name, different keys
  private final AlbumKey key = new AlbumKey("album-1", 1, Aead.newKey());
  private final AlbumKeyring keyring = new AlbumKeyring("album-1");

  SealedCatalogTest() {
    keyring.add(key);
  }

  private Catalog catalogWithOnePhoto() {
    return Catalog.empty("album-1", "alice").with(new PhotoEntry("p1", "beach.jpg", "blob/p1", 1, "00", 3));
  }

  @Test
  void opensWhatItSealed() {
    SealedCatalog sealed = SealedCatalog.seal(catalogWithOnePhoto(), key, ALICE);
    Catalog opened = SealedCatalog.decode(sealed.encode()).open("album-1", ALICE.publicIdentity(), 0, keyring);
    assertEquals("beach.jpg", opened.photos().get(0).name());
    assertEquals(1, opened.version());
  }

  @Test
  void rejectsACatalogSignedBySomeoneElseUnderTheSameName() {
    SealedCatalog forged = SealedCatalog.seal(catalogWithOnePhoto(), key, MALLORY);
    assertThrows(CryptoException.class, () -> forged.open("album-1", ALICE.publicIdentity(), 0, keyring));
  }

  @Test
  void rejectsATamperedCiphertext() {
    SealedCatalog sealed = SealedCatalog.seal(catalogWithOnePhoto(), key, ALICE);
    byte[] bytes = sealed.ciphertext().clone();
    bytes[bytes.length - 1] ^= 1;
    SealedCatalog tampered = new SealedCatalog(sealed.albumId(), sealed.owner(), sealed.epoch(), sealed.version(),
        bytes, sealed.signature());
    assertThrows(CryptoException.class, () -> tampered.open("album-1", ALICE.publicIdentity(), 0, keyring));
  }

  @Test
  void rejectsAnEditedHeader() {
    SealedCatalog sealed = SealedCatalog.seal(catalogWithOnePhoto(), key, ALICE);
    SealedCatalog bumped = new SealedCatalog(sealed.albumId(), sealed.owner(), sealed.epoch(), 99,
        sealed.ciphertext(), sealed.signature());
    assertThrows(CryptoException.class, () -> bumped.open("album-1", ALICE.publicIdentity(), 0, keyring));
  }

  @Test
  void rejectsACatalogFromAnotherAlbum() {
    SealedCatalog sealed = SealedCatalog.seal(catalogWithOnePhoto(), key, ALICE);
    assertThrows(CryptoException.class, () -> sealed.open("album-2", ALICE.publicIdentity(), 0, keyring));
  }

  @Test
  void rejectsARollbackToAnOlderVersion() {
    SealedCatalog v1 = SealedCatalog.seal(catalogWithOnePhoto(), key, ALICE);
    assertThrows(CryptoException.class, () -> v1.open("album-1", ALICE.publicIdentity(), 2, keyring));
  }

  @Test
  void cannotBeOpenedWithoutTheKeyForItsEpoch() {
    AlbumKey later = new AlbumKey("album-1", 2, Aead.newKey());
    SealedCatalog sealed = SealedCatalog.seal(catalogWithOnePhoto(), later, ALICE);
    assertThrows(CryptoException.class, () -> sealed.open("album-1", ALICE.publicIdentity(), 0, keyring));
  }

  @Test
  void aMemberCannotSealSomeoneElsesCatalog() {
    assertThrows(IllegalArgumentException.class,
        () -> SealedCatalog.seal(Catalog.empty("album-1", "bob"), key, ALICE));
  }
}
