package dev.sobatista.tessera.core.album;

/**
 * One photo in a member's catalog.
 *
 * @param photoId   random id, unique within the album
 * @param name      the display name the member gave it (encrypted along with the rest of the catalog)
 * @param blobName  where the encrypted bytes live in the owner's storage
 * @param epoch     which album-key epoch the photo was encrypted under
 * @param sha256    hash of the plaintext, checked after decryption
 * @param size      plaintext size in bytes
 */
public record PhotoEntry(String photoId, String name, String blobName, long epoch, String sha256, long size) {}
