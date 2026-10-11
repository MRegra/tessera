package dev.sobatista.tessera.core.storage;

import java.util.Optional;

/**
 * A member's own storage: their slice of "the cloud" (a Dropbox folder, an S3 bucket, a NAS share...).
 *
 * <p>Tessera assumes the worst about storage: whoever hosts it can read and modify everything in it. That's fine,
 * because everything Tessera writes here is already encrypted and the catalog is signed; the host learns sizes and
 * timing, nothing more.
 */
public interface BlobStore {

  /** A stable name other members use to find this store (it goes into catalog pointers). */
  String id();

  void put(String name, byte[] data);

  Optional<byte[]> get(String name);
}
