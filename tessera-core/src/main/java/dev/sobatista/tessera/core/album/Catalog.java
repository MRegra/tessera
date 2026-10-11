package dev.sobatista.tessera.core.album;

import java.util.ArrayList;
import java.util.List;

/**
 * One member's slice of an album — their tessera in the mosaic: the list of photos <em>they</em> contributed, which
 * live in <em>their</em> storage.
 *
 * <p>An album is the union of all current members' catalogs. Nobody, not even the album owner, can write into another
 * member's catalog: each member is the only author of their own, and signs it.
 *
 * @param version strictly increasing per (album, owner); lets readers refuse an older catalog replayed at them
 */
public record Catalog(String albumId, String owner, long version, List<PhotoEntry> photos) {

  public Catalog {
    photos = List.copyOf(photos);
  }

  public static Catalog empty(String albumId, String owner) {
    return new Catalog(albumId, owner, 0, List.of());
  }

  public Catalog with(PhotoEntry photo) {
    List<PhotoEntry> next = new ArrayList<>(photos);
    next.add(photo);
    return new Catalog(albumId, owner, version + 1, next);
  }

  byte[] encode() {
    Codec.Writer writer = new Codec.Writer().string(albumId).string(owner).longValue(version).longValue(photos.size());
    for (PhotoEntry p : photos) {
      writer.string(p.photoId()).string(p.name()).string(p.blobName()).longValue(p.epoch()).string(p.sha256())
          .longValue(p.size());
    }
    return writer.toByteArray();
  }

  static Catalog decode(byte[] data) {
    Codec.Reader reader = new Codec.Reader(data);
    String albumId = reader.string();
    String owner = reader.string();
    long version = reader.longValue();
    long count = reader.longValue();
    if (count < 0 || count > 1_000_000) {
      throw new IllegalArgumentException("implausible photo count " + count);
    }
    List<PhotoEntry> photos = new ArrayList<>();
    for (long i = 0; i < count; i++) {
      photos.add(new PhotoEntry(reader.string(), reader.string(), reader.string(), reader.longValue(),
          reader.string(), reader.longValue()));
    }
    reader.expectEnd();
    return new Catalog(albumId, owner, version, photos);
  }
}
