package dev.sobatista.tessera.core.album;

import javax.crypto.SecretKey;

/**
 * The symmetric key every member of an album uses to encrypt and decrypt its contents, at one <em>epoch</em>.
 *
 * <p>The epoch is a counter that goes up every time the key is replaced. It is replaced when a member is removed: the
 * removed member may have copied the old key, so everything written <em>after</em> the removal uses a key they never
 * received. Each ciphertext records the epoch it was written under, so old photos stay readable with old keys.
 */
public record AlbumKey(String albumId, long epoch, SecretKey key) {

  public AlbumKey {
    if (epoch < 1) {
      throw new IllegalArgumentException("epochs start at 1");
    }
  }
}
