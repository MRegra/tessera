package dev.sobatista.tessera.core.album;

import dev.sobatista.tessera.core.crypto.Aead;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.crypto.Identity;
import dev.sobatista.tessera.core.crypto.PublicIdentity;

/**
 * A catalog as it is stored: encrypted with the album key, then signed by its owner.
 *
 * <p>Layout: {@code header || ciphertext}, then a signature over both. The header is in the clear — album id, owner,
 * epoch and version — because a reader needs it to pick the right key and to check freshness <em>before</em>
 * decrypting. The header is also the AES-GCM associated data, so it can't be swapped onto someone else's ciphertext.
 *
 * <p>Order of checks when opening, and why:
 * <ol>
 *   <li>The header names the album and owner we asked for (no catalog from another album, or another member, passed
 *       off as this one).</li>
 *   <li>The owner's signature verifies (the storage host or the server can't forge or edit it).</li>
 *   <li>The version is not older than one we've already seen (no rollback to hide newer photos).</li>
 *   <li>Only then decrypt, with the key for the stated epoch.</li>
 * </ol>
 */
public record SealedCatalog(String albumId, String owner, long epoch, long version, byte[] ciphertext,
    byte[] signature) {

  public static SealedCatalog seal(Catalog catalog, AlbumKey key, Identity owner) {
    if (!catalog.owner().equals(owner.userId())) {
      throw new IllegalArgumentException("only " + catalog.owner() + " can seal their own catalog");
    }
    byte[] header = header(catalog.albumId(), catalog.owner(), key.epoch(), catalog.version());
    byte[] ciphertext = Aead.encrypt(key.key(), catalog.encode(), header);
    byte[] signature = owner.sign(signedBytes(header, ciphertext));
    return new SealedCatalog(catalog.albumId(), catalog.owner(), key.epoch(), catalog.version(), ciphertext,
        signature);
  }

  /**
   * Verifies and decrypts. {@code minVersion} is the highest version this reader has already accepted for this owner
   * (0 if none).
   */
  public Catalog open(String expectedAlbumId, PublicIdentity expectedOwner, long minVersion, AlbumKeyring keys) {
    if (!albumId.equals(expectedAlbumId) || !owner.equals(expectedOwner.userId())) {
      throw new CryptoException("catalog header names " + owner + "@" + albumId + ", expected "
          + expectedOwner.userId() + "@" + expectedAlbumId);
    }
    byte[] header = header(albumId, owner, epoch, version);
    if (!expectedOwner.verify(signedBytes(header, ciphertext), signature)) {
      throw new CryptoException("catalog signature from " + owner + " does not verify: it was forged or modified");
    }
    if (version < minVersion) {
      throw new CryptoException("rollback: " + owner + "'s catalog is at version " + version
          + " but version " + minVersion + " was already seen");
    }
    Catalog catalog = Catalog.decode(Aead.decrypt(keys.key(epoch).key(), ciphertext, header));
    if (!catalog.albumId().equals(albumId) || !catalog.owner().equals(owner) || catalog.version() != version) {
      throw new CryptoException("catalog body does not match its signed header");
    }
    return catalog;
  }

  public byte[] encode() {
    return new Codec.Writer().string(albumId).string(owner).longValue(epoch).longValue(version).bytes(ciphertext)
        .bytes(signature).toByteArray();
  }

  public static SealedCatalog decode(byte[] data) {
    Codec.Reader reader = new Codec.Reader(data);
    SealedCatalog sealed = new SealedCatalog(reader.string(), reader.string(), reader.longValue(),
        reader.longValue(), reader.bytes(), reader.bytes());
    reader.expectEnd();
    return sealed;
  }

  private static byte[] header(String albumId, String owner, long epoch, long version) {
    return new Codec.Writer().string("tessera-catalog-v1").string(albumId).string(owner).longValue(epoch)
        .longValue(version).toByteArray();
  }

  private static byte[] signedBytes(byte[] header, byte[] ciphertext) {
    return new Codec.Writer().bytes(header).bytes(ciphertext).toByteArray();
  }
}
