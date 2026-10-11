package dev.sobatista.tessera.core.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Storage that lives in memory: for tests and the demo. */
public final class InMemoryBlobStore implements BlobStore {

  private final String id;
  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

  public InMemoryBlobStore(String id) {
    this.id = id;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void put(String name, byte[] data) {
    blobs.put(name, data.clone());
  }

  @Override
  public Optional<byte[]> get(String name) {
    return Optional.ofNullable(blobs.get(name)).map(byte[]::clone);
  }
}
