package dev.sobatista.tessera.core;

import dev.sobatista.tessera.core.album.PhotoEntry;

/** A photo as one member sees it in an album: whose slice it came from, and its catalog entry. */
public record VisiblePhoto(String albumId, String owner, String ownerStorePointerPrefix, PhotoEntry entry) {}
