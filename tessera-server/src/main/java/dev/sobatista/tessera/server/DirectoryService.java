package dev.sobatista.tessera.server;

import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.BAD_REQUEST;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.CONFLICT;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.FORBIDDEN;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.NOT_FOUND;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.UNAUTHENTICATED;

import dev.sobatista.tessera.core.crypto.PublicIdentity;
import dev.sobatista.tessera.core.directory.AlbumView;
import dev.sobatista.tessera.core.directory.Directory;
import dev.sobatista.tessera.core.directory.DirectoryException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The directory's rules, independent of any transport. {@link TesseraServer} exposes it over HTTP; tests call it
 * directly. All state is in memory and every method is {@code synchronized}: simple to reason about, and the directory
 * holds so little data that it doesn't need to be fast. Persistence is on the roadmap.
 */
public final class DirectoryService implements Directory {

  private static final Pattern USER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");
  private static final int MIN_PASSWORD = 10;
  private static final int MAX_POINTER = 512;
  private static final Duration SESSION_TTL = Duration.ofHours(12);
  private static final SecureRandom RANDOM = new SecureRandom();

  private record Account(PublicIdentity identity, Passwords.Hash password) {}

  private record Session(String userId, Instant expires) {}

  private static final class Album {
    final String id;
    final String name;
    final String owner;
    long epoch = 1;
    final Set<String> members = new LinkedHashSet<>();
    final Map<String, String> pointers = new HashMap<>();
    /** member -> epoch -> wrapped key */
    final Map<String, Map<Long, byte[]>> wrapped = new HashMap<>();

    Album(String id, String name, String owner) {
      this.id = id;
      this.name = name;
      this.owner = owner;
    }

    AlbumView view() {
      return new AlbumView(id, name, owner, epoch, new ArrayList<>(members), pointers);
    }
  }

  private final Passwords passwords;
  private final Clock clock;
  private final Map<String, Account> accounts = new HashMap<>();
  private final Map<String, Session> sessions = new HashMap<>();
  private final Map<String, Album> albums = new HashMap<>();

  public DirectoryService() {
    this(Passwords.DEFAULT_ITERATIONS, Clock.systemUTC());
  }

  /** Tests use fewer PBKDF2 iterations and a controllable clock. */
  DirectoryService(int passwordIterations, Clock clock) {
    this.passwords = new Passwords(passwordIterations);
    this.clock = clock;
  }

  public static DirectoryService forTests() {
    return new DirectoryService(1_000, Clock.systemUTC());
  }

  // ---------------------------------------------------------------- accounts

  @Override
  public synchronized void register(String userId, char[] password, PublicIdentity identity) {
    try {
      if (userId == null || !USER_ID.matcher(userId).matches()) {
        throw new DirectoryException(BAD_REQUEST, "user ids are 3-32 chars of a-z, 0-9, '.', '_', '-'");
      }
      if (identity == null || !identity.userId().equals(userId)) {
        throw new DirectoryException(BAD_REQUEST, "public identity must be for " + userId);
      }
      if (password == null || password.length < MIN_PASSWORD) {
        throw new DirectoryException(BAD_REQUEST, "passwords need at least " + MIN_PASSWORD + " characters");
      }
      if (accounts.containsKey(userId)) {
        throw new DirectoryException(CONFLICT, "user " + userId + " already exists");
      }
      accounts.put(userId, new Account(identity, passwords.hash(password)));
    } finally {
      if (password != null) {
        Passwords.wipe(password);
      }
    }
  }

  @Override
  public synchronized String login(String userId, char[] password) {
    try {
      Account account = userId == null ? null : accounts.get(userId);
      boolean ok;
      if (account == null) {
        passwords.burnTime(password == null ? new char[0] : password);
        ok = false;
      } else {
        ok = password != null && passwords.matches(password, account.password());
      }
      if (!ok) {
        throw new DirectoryException(UNAUTHENTICATED, "wrong user id or password"); // never says which
      }
      byte[] token = new byte[32];
      RANDOM.nextBytes(token);
      String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
      sessions.put(encoded, new Session(userId, clock.instant().plus(SESSION_TTL)));
      return encoded;
    } finally {
      if (password != null) {
        Passwords.wipe(password);
      }
    }
  }

  @Override
  public synchronized PublicIdentity publicIdentity(String token, String userId) {
    caller(token);
    Account account = accounts.get(userId);
    if (account == null) {
      throw new DirectoryException(NOT_FOUND, "no user " + userId);
    }
    return account.identity();
  }

  // ---------------------------------------------------------------- albums

  @Override
  public synchronized String createAlbum(String token, String name, byte[] ownerWrappedKey) {
    String owner = caller(token);
    if (name == null || name.isBlank() || name.length() > 100) {
      throw new DirectoryException(BAD_REQUEST, "album names are 1-100 characters");
    }
    requireKey(ownerWrappedKey);
    Album album = new Album(UUID.randomUUID().toString(), name, owner);
    album.members.add(owner);
    album.wrapped.put(owner, new HashMap<>(Map.of(1L, ownerWrappedKey.clone())));
    albums.put(album.id, album);
    return album.id;
  }

  @Override
  public synchronized AlbumView album(String token, String albumId) {
    return memberAlbum(caller(token), albumId).view();
  }

  @Override
  public synchronized List<AlbumView> myAlbums(String token) {
    String me = caller(token);
    return albums.values().stream().filter(a -> a.members.contains(me)).map(Album::view).toList();
  }

  @Override
  public synchronized void addMember(String token, String albumId, String userId,
      Map<Long, byte[]> wrappedKeysByEpoch) {
    Album album = ownedAlbum(caller(token), albumId);
    if (!accounts.containsKey(userId)) {
      throw new DirectoryException(NOT_FOUND, "no user " + userId);
    }
    if (album.members.contains(userId)) {
      throw new DirectoryException(CONFLICT, userId + " is already a member");
    }
    if (wrappedKeysByEpoch == null || !wrappedKeysByEpoch.containsKey(album.epoch)) {
      throw new DirectoryException(BAD_REQUEST, "a new member needs the current epoch's key (" + album.epoch + ")");
    }
    Map<Long, byte[]> keys = new HashMap<>();
    wrappedKeysByEpoch.forEach((epoch, key) -> {
      if (epoch < 1 || epoch > album.epoch) {
        throw new DirectoryException(BAD_REQUEST, "epoch " + epoch + " does not exist yet");
      }
      requireKey(key);
      keys.put(epoch, key.clone());
    });
    album.members.add(userId);
    album.wrapped.put(userId, keys);
  }

  @Override
  public synchronized void removeMember(String token, String albumId, String userId, long newEpoch,
      Map<String, byte[]> newWrappedKeys) {
    String owner = caller(token);
    Album album = ownedAlbum(owner, albumId);
    if (userId.equals(owner)) {
      throw new DirectoryException(BAD_REQUEST, "the owner cannot remove themselves");
    }
    if (!album.members.contains(userId)) {
      throw new DirectoryException(NOT_FOUND, userId + " is not a member");
    }
    if (newEpoch != album.epoch + 1) {
      throw new DirectoryException(CONFLICT, "expected epoch " + (album.epoch + 1) + ", got " + newEpoch
          + " (someone else changed the album; reload and retry)");
    }
    Set<String> remaining = new LinkedHashSet<>(album.members);
    remaining.remove(userId);
    if (newWrappedKeys == null || !newWrappedKeys.keySet().equals(remaining)) {
      throw new DirectoryException(BAD_REQUEST, "the new key must be wrapped for exactly the remaining members "
          + remaining);
    }
    newWrappedKeys.values().forEach(DirectoryService::requireKey);
    // Apply everything together: no half-rotated album is ever visible.
    album.members.remove(userId);
    album.pointers.remove(userId);
    album.wrapped.remove(userId);
    album.epoch = newEpoch;
    newWrappedKeys.forEach((member, key) -> album.wrapped.get(member).put(newEpoch, key.clone()));
  }

  @Override
  public synchronized Map<Long, byte[]> myWrappedKeys(String token, String albumId) {
    String me = caller(token);
    Map<Long, byte[]> copy = new LinkedHashMap<>();
    memberAlbum(me, albumId).wrapped.get(me).forEach((epoch, key) -> copy.put(epoch, key.clone()));
    return copy;
  }

  @Override
  public synchronized void setCatalogPointer(String token, String albumId, String pointer) {
    String me = caller(token);
    Album album = memberAlbum(me, albumId);
    if (pointer == null || pointer.length() > MAX_POINTER || pointer.indexOf(':') <= 0) {
      throw new DirectoryException(BAD_REQUEST, "pointers look like storeId:blobName (max " + MAX_POINTER + ")");
    }
    album.pointers.put(me, pointer);
  }

  // ---------------------------------------------------------------- checks

  private String caller(String token) {
    Session session = token == null ? null : sessions.get(token);
    if (session == null || clock.instant().isAfter(session.expires())) {
      if (session != null) {
        sessions.remove(token);
      }
      throw new DirectoryException(UNAUTHENTICATED, "log in first (session missing or expired)");
    }
    return session.userId();
  }

  /** Non-members get NOT_FOUND, not FORBIDDEN: they shouldn't learn that the album exists. */
  private Album memberAlbum(String userId, String albumId) {
    Album album = albums.get(albumId);
    if (album == null || !album.members.contains(userId)) {
      throw new DirectoryException(NOT_FOUND, "no album " + albumId);
    }
    return album;
  }

  private Album ownedAlbum(String userId, String albumId) {
    Album album = memberAlbum(userId, albumId);
    if (!album.owner.equals(userId)) {
      throw new DirectoryException(FORBIDDEN, "only the album owner can change its members");
    }
    return album;
  }

  private static void requireKey(byte[] wrapped) {
    if (wrapped == null || wrapped.length == 0 || wrapped.length > 1024) {
      throw new DirectoryException(BAD_REQUEST, "malformed wrapped key");
    }
  }
}
