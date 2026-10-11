package dev.sobatista.tessera.core;

import dev.sobatista.tessera.core.album.AlbumKey;
import dev.sobatista.tessera.core.album.AlbumKeyring;
import dev.sobatista.tessera.core.album.Catalog;
import dev.sobatista.tessera.core.album.PhotoEntry;
import dev.sobatista.tessera.core.album.SealedCatalog;
import dev.sobatista.tessera.core.crypto.Aead;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.crypto.Identity;
import dev.sobatista.tessera.core.crypto.PublicIdentity;
import dev.sobatista.tessera.core.directory.AlbumView;
import dev.sobatista.tessera.core.directory.Directory;
import dev.sobatista.tessera.core.storage.BlobStore;
import dev.sobatista.tessera.core.storage.StorageNetwork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One member's client: the only place where album keys, catalogs and photos exist in the clear.
 *
 * <p>The three parties and what each one holds:
 * <ul>
 *   <li><b>This member</b>: their private keys, the album keys they unwrapped, plaintext photos.</li>
 *   <li><b>The directory server</b>: membership, catalog pointers, wrapped keys. No plaintext of anything.</li>
 *   <li><b>Each member's storage</b>: that member's encrypted photos and their signed, encrypted catalog.</li>
 * </ul>
 */
public final class Member {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Identity identity;
  private final Directory directory;
  private final BlobStore ownStore;
  private final StorageNetwork network;
  private final String session;
  private final Map<String, AlbumKeyring> keyrings = new HashMap<>();
  /** Highest catalog version accepted per album and owner: refuses rollbacks. */
  private final Map<String, Long> lastSeenVersion = new HashMap<>();

  private Member(Identity identity, Directory directory, BlobStore ownStore, StorageNetwork network, String session) {
    this.identity = identity;
    this.directory = directory;
    this.ownStore = ownStore;
    this.network = network;
    this.session = session;
  }

  /** Creates keys on this device, registers the public half, and logs in. */
  public static Member signUp(String userId, char[] password, Directory directory, BlobStore ownStore,
      StorageNetwork network) {
    Identity identity = Identity.generate(userId);
    // Directories wipe the password array they receive, so each call gets its own copy.
    directory.register(userId, password.clone(), identity.publicIdentity());
    network.publish(ownStore);
    return new Member(identity, directory, ownStore, network, directory.login(userId, password.clone()));
  }

  public String userId() {
    return identity.userId();
  }

  public String fingerprint() {
    return identity.publicIdentity().fingerprint();
  }

  /** The fingerprint the <em>server</em> claims another member has; compare it with theirs out of band. */
  public String fingerprintOf(String userId) {
    return directory.publicIdentity(session, userId).fingerprint();
  }

  // ---------------------------------------------------------------- albums and membership

  public String createAlbum(String name) {
    javax.crypto.SecretKey first = Aead.newKey();
    String albumId = directory.createAlbum(session, name, identity.publicIdentity().wrap(first));
    keyring(albumId).add(new AlbumKey(albumId, 1, first));
    return albumId;
  }

  /** Owner only: shares every key epoch we hold, so the new member can read the album's whole history. */
  public void invite(String albumId, String userId) {
    syncKeys(albumId);
    PublicIdentity invitee = directory.publicIdentity(session, userId);
    Map<Long, byte[]> wrapped = new LinkedHashMap<>();
    keyring(albumId).all().forEach((epoch, key) -> wrapped.put(epoch, invitee.wrap(key.key())));
    directory.addMember(session, albumId, userId, wrapped);
  }

  /**
   * Owner only: removes a member and rotates the album key. Everything written from now on uses the new epoch, which
   * is wrapped only for the remaining members.
   *
   * <p>What rotation can and can't do: the removed member keeps whatever they already downloaded or could already
   * decrypt — no system can make someone forget. What they lose is everything <em>new</em>: new photos, and the
   * catalogs every remaining member re-seals under the new key.
   */
  public void remove(String albumId, String userId) {
    syncKeys(albumId);
    AlbumView view = directory.album(session, albumId);
    long newEpoch = view.epoch() + 1;
    AlbumKey next = new AlbumKey(albumId, newEpoch, Aead.newKey());
    Map<String, byte[]> wrapped = new LinkedHashMap<>();
    for (String member : view.members()) {
      if (!member.equals(userId)) {
        wrapped.put(member, directory.publicIdentity(session, member).wrap(next.key()));
      }
    }
    directory.removeMember(session, albumId, userId, newEpoch, wrapped);
    keyring(albumId).add(next);
    resealOwnCatalog(albumId);
  }

  // ---------------------------------------------------------------- photos

  /** Encrypts a photo with the current album key, stores it in our own storage, and adds it to our catalog. */
  public String addPhoto(String albumId, String name, byte[] photo) {
    syncKeys(albumId);
    AlbumKey key = keyring(albumId).current();
    String photoId = randomId();
    String blobName = "tessera/" + albumId + "/photos/" + photoId;
    ownStore.put(blobName, Aead.encrypt(key.key(), photo, photoAad(albumId, userId(), photoId, key.epoch())));
    Catalog next = ownCatalog(albumId).with(new PhotoEntry(photoId, name, blobName, key.epoch(), sha256(photo),
        photo.length));
    publishCatalog(albumId, next, key);
    return photoId;
  }

  /**
   * Everything this member can currently see in the album: the union of every current member's verified catalog.
   * A catalog that fails verification is reported, never silently skipped or trusted.
   */
  public List<VisiblePhoto> view(String albumId) {
    syncKeys(albumId);
    resealOwnCatalogIfStale(albumId);
    AlbumView view = directory.album(session, albumId);
    List<VisiblePhoto> photos = new ArrayList<>();
    for (String member : view.members()) {
      String pointer = view.catalogPointers().get(member);
      if (pointer == null) {
        continue; // hasn't contributed anything yet
      }
      Catalog catalog = readCatalog(albumId, member, pointer);
      String prefix = pointer.substring(0, pointer.indexOf(':') + 1);
      catalog.photos().forEach(p -> photos.add(new VisiblePhoto(albumId, member, prefix, p)));
    }
    return photos;
  }

  /** Downloads and decrypts one photo from its owner's storage, checking it's exactly what their catalog promised. */
  public byte[] open(VisiblePhoto photo) {
    PhotoEntry entry = photo.entry();
    byte[] sealed = network.fetch(photo.ownerStorePointerPrefix() + entry.blobName())
        .orElseThrow(() -> new CryptoException(photo.owner() + "'s storage no longer has " + entry.name()));
    AlbumKey key = keyring(photo.albumId()).key(entry.epoch());
    byte[] plain = Aead.decrypt(key.key(), sealed, photoAad(photo.albumId(), photo.owner(), entry.photoId(),
        entry.epoch()));
    if (!sha256(plain).equals(entry.sha256())) {
      throw new CryptoException("photo " + entry.name() + " does not match its catalog hash");
    }
    return plain;
  }

  // ---------------------------------------------------------------- internals

  private void syncKeys(String albumId) {
    directory.myWrappedKeys(session, albumId).forEach((epoch, wrapped) -> {
      if (!keyring(albumId).has(epoch)) {
        keyring(albumId).add(new AlbumKey(albumId, epoch, identity.unwrap(wrapped)));
      }
    });
  }

  private Catalog readCatalog(String albumId, String member, String pointer) {
    byte[] bytes = network.fetch(pointer)
        .orElseThrow(() -> new CryptoException(member + "'s catalog is missing from their storage"));
    String seenKey = albumId + "/" + member;
    Catalog catalog = SealedCatalog.decode(bytes).open(albumId, directory.publicIdentity(session, member),
        lastSeenVersion.getOrDefault(seenKey, 0L), keyring(albumId));
    lastSeenVersion.merge(seenKey, catalog.version(), Math::max);
    return catalog;
  }

  private Catalog ownCatalog(String albumId) {
    return ownStore.get(catalogBlob(albumId))
        .map(bytes -> SealedCatalog.decode(bytes).open(albumId, identity.publicIdentity(), 0, keyring(albumId)))
        .orElse(Catalog.empty(albumId, userId()));
  }

  private void publishCatalog(String albumId, Catalog catalog, AlbumKey key) {
    ownStore.put(catalogBlob(albumId), SealedCatalog.seal(catalog, key, identity).encode());
    directory.setCatalogPointer(session, albumId, StorageNetwork.pointer(ownStore, catalogBlob(albumId)));
  }

  /** After a rotation our catalog is still sealed under the old key; re-seal it so only current members can read it. */
  private void resealOwnCatalogIfStale(String albumId) {
    ownStore.get(catalogBlob(albumId)).ifPresent(bytes -> {
      if (SealedCatalog.decode(bytes).epoch() < keyring(albumId).current().epoch()) {
        resealOwnCatalog(albumId);
      }
    });
  }

  private void resealOwnCatalog(String albumId) {
    if (ownStore.get(catalogBlob(albumId)).isEmpty()) {
      return;
    }
    Catalog current = ownCatalog(albumId);
    Catalog bumped = new Catalog(albumId, userId(), current.version() + 1, current.photos());
    publishCatalog(albumId, bumped, keyring(albumId).current());
  }

  private AlbumKeyring keyring(String albumId) {
    return keyrings.computeIfAbsent(albumId, AlbumKeyring::new);
  }

  private static String catalogBlob(String albumId) {
    return "tessera/" + albumId + "/catalog";
  }

  private static byte[] photoAad(String albumId, String owner, String photoId, long epoch) {
    return ("tessera-photo-v1|" + albumId + "|" + owner + "|" + photoId + "|" + epoch)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String randomId() {
    byte[] id = new byte[12];
    RANDOM.nextBytes(id);
    return HexFormat.of().formatHex(id);
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
