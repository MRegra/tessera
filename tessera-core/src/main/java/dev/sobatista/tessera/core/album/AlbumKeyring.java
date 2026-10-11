package dev.sobatista.tessera.core.album;

import dev.sobatista.tessera.core.crypto.CryptoException;
import java.util.Map;
import java.util.TreeMap;

/** All the epochs of one album's key that a member holds. The newest one is used for writing. */
public final class AlbumKeyring {

  private final String albumId;
  private final TreeMap<Long, AlbumKey> byEpoch = new TreeMap<>();

  public AlbumKeyring(String albumId) {
    this.albumId = albumId;
  }

  public void add(AlbumKey key) {
    if (!key.albumId().equals(albumId)) {
      throw new IllegalArgumentException("key for " + key.albumId() + " added to keyring of " + albumId);
    }
    byEpoch.put(key.epoch(), key);
  }

  public AlbumKey key(long epoch) {
    AlbumKey key = byEpoch.get(epoch);
    if (key == null) {
      throw new CryptoException("no album key for epoch " + epoch + " of " + albumId
          + " (written after this member was removed, or never shared with them)");
    }
    return key;
  }

  public AlbumKey current() {
    if (byEpoch.isEmpty()) {
      throw new CryptoException("no key at all for album " + albumId);
    }
    return byEpoch.lastEntry().getValue();
  }

  public boolean has(long epoch) {
    return byEpoch.containsKey(epoch);
  }

  public Map<Long, AlbumKey> all() {
    return Map.copyOf(byEpoch);
  }
}
