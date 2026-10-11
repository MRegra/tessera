package dev.sobatista.tessera.core.directory;

import java.util.List;
import java.util.Map;

/**
 * What a member sees of an album from the directory: its members, the current key epoch, and where each member's
 * catalog lives. Note what's missing: no photos, no names of photos, no keys.
 */
public record AlbumView(String albumId, String name, String owner, long epoch, List<String> members,
    Map<String, String> catalogPointers) {

  public AlbumView {
    members = List.copyOf(members);
    catalogPointers = Map.copyOf(catalogPointers);
  }
}
