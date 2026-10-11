package dev.sobatista.tessera.core.directory;

import dev.sobatista.tessera.core.crypto.PublicIdentity;
import java.util.List;
import java.util.Map;

/**
 * Everything the server knows, and therefore everything it could leak: who exists, who is in which album, where each
 * member's catalog lives, and the album keys <em>wrapped</em> for each member (which it cannot unwrap).
 *
 * <p>It never sees a photo, a catalog's contents, or an album key in the clear. Compare the original course project,
 * whose server also stored every user's cloud-storage access token: a single breach of it was a breach of every
 * user's storage. Tessera's server holds nothing that opens anyone's storage or anyone's photos.
 *
 * <p>Calls take a session token from {@link #login}. Authorization lives here, in the server: only members may read
 * an album, only its owner may add or remove members, a member may only point to their own catalog.
 */
public interface Directory {

  void register(String userId, char[] password, PublicIdentity identity);

  /** Returns an opaque session token. */
  String login(String userId, char[] password);

  PublicIdentity publicIdentity(String token, String userId);

  /** Creates an album owned by the caller, at key epoch 1, storing the owner's own wrapped copy of that key. */
  String createAlbum(String token, String name, byte[] ownerWrappedKey);

  AlbumView album(String token, String albumId);

  List<AlbumView> myAlbums(String token);

  /** Owner only. {@code wrappedKeysByEpoch}: every epoch the new member should be able to read, wrapped for them. */
  void addMember(String token, String albumId, String userId, Map<Long, byte[]> wrappedKeysByEpoch);

  /**
   * Owner only. Removes {@code userId} and moves the album to {@code newEpoch} (current + 1) in one step, with the
   * new key wrapped for exactly every remaining member — so there is never a moment where the album has a new member
   * list but some remaining member can't read new content, or a removed member could.
   */
  void removeMember(String token, String albumId, String userId, long newEpoch, Map<String, byte[]> newWrappedKeys);

  /** The caller's wrapped copies of the album key, by epoch. */
  Map<Long, byte[]> myWrappedKeys(String token, String albumId);

  /** Sets where the caller's own catalog for this album lives ({@code storeId:blobName}). */
  void setCatalogPointer(String token, String albumId, String pointer);
}
