package dev.sobatista.tessera.core.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a member reaches <em>other</em> members' storage: read-only, by store id.
 *
 * <p>In real life this is "open the shared link": the URLs are effectively public, and whoever has one can download
 * the ciphertext. That's the honest model — access control to storage is not what keeps photos private; the album key
 * is. So this network deliberately hands out read access to anyone who asks.
 */
public final class StorageNetwork {

  private final Map<String, BlobStore> stores = new ConcurrentHashMap<>();

  public void publish(BlobStore store) {
    stores.put(store.id(), store);
  }

  /** A pointer is {@code storeId:blobName}. */
  public Optional<byte[]> fetch(String pointer) {
    int colon = pointer.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("malformed pointer " + pointer);
    }
    BlobStore store = stores.get(pointer.substring(0, colon));
    return store == null ? Optional.empty() : store.get(pointer.substring(colon + 1));
  }

  public static String pointer(BlobStore store, String blobName) {
    return store.id() + ":" + blobName;
  }
}
