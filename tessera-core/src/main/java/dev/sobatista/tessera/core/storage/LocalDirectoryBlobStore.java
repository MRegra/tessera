package dev.sobatista.tessera.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Storage in a local folder: the simplest real "cloud". Point it at a synced folder (Dropbox, Nextcloud, a NAS
 * mount) and that provider becomes the member's storage, with no provider credentials anywhere near Tessera.
 */
public final class LocalDirectoryBlobStore implements BlobStore {

  private final String id;
  private final Path root;

  public LocalDirectoryBlobStore(String id, Path root) {
    this.id = id;
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void put(String name, byte[] data) {
    Path target = resolve(name);
    try {
      Files.createDirectories(target.getParent());
      Path temp = Files.createTempFile(target.getParent(), ".tessera-", ".tmp");
      Files.write(temp, data);
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + name + " to " + id, e);
    }
  }

  @Override
  public Optional<byte[]> get(String name) {
    Path target = resolve(name);
    try {
      return Files.exists(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + name + " from " + id, e);
    }
  }

  /** Blob names come from catalogs other members wrote, so never let one escape the root folder. */
  private Path resolve(String name) {
    Path target = root.resolve(name).normalize();
    if (!target.startsWith(root) || target.equals(root)) {
      throw new IllegalArgumentException("blob name escapes the storage folder: " + name);
    }
    return target;
  }
}
