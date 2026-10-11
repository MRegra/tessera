package dev.sobatista.tessera.server;

import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.BAD_REQUEST;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.CONFLICT;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.FORBIDDEN;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.NOT_FOUND;
import static dev.sobatista.tessera.core.directory.DirectoryException.Reason.UNAUTHENTICATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sobatista.tessera.core.crypto.Identity;
import dev.sobatista.tessera.core.directory.DirectoryException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class DirectoryServiceTest {

  private static final byte[] KEY = {1, 2, 3};
  private final DirectoryService directory = DirectoryService.forTests();

  private String signUp(String user) {
    directory.register(user, "correct horse battery".toCharArray(), Identity.generate(user).publicIdentity());
    return directory.login(user, "correct horse battery".toCharArray());
  }

  private static void assertReason(DirectoryException.Reason reason, Executable call) {
    assertEquals(reason, assertThrows(DirectoryException.class, call).reason());
  }

  @Test
  void wrongPasswordAndUnknownUserGiveTheSameAnswer() {
    signUp("alice");
    DirectoryException wrong = assertThrows(DirectoryException.class,
        () -> directory.login("alice", "not the password".toCharArray()));
    DirectoryException unknown = assertThrows(DirectoryException.class,
        () -> directory.login("nobody", "not the password".toCharArray()));
    assertEquals(UNAUTHENTICATED, wrong.reason());
    assertEquals(wrong.getMessage(), unknown.getMessage());
  }

  @Test
  void rejectsShortPasswordsBadIdsAndDuplicates() {
    assertReason(BAD_REQUEST, () -> directory.register("alice", "short".toCharArray(),
        Identity.generate("alice").publicIdentity()));
    assertReason(BAD_REQUEST, () -> directory.register("Alice!", "long enough pass".toCharArray(),
        Identity.generate("Alice!").publicIdentity()));
    signUp("alice");
    assertReason(CONFLICT, () -> signUp("alice"));
  }

  @Test
  void everythingNeedsAValidSession() {
    assertReason(UNAUTHENTICATED, () -> directory.myAlbums(null));
    assertReason(UNAUTHENTICATED, () -> directory.myAlbums("made-up-token"));
  }

  @Test
  void sessionsExpire() {
    MutableClock clock = new MutableClock();
    DirectoryService timed = new DirectoryService(1_000, clock);
    timed.register("alice", "correct horse battery".toCharArray(), Identity.generate("alice").publicIdentity());
    String token = timed.login("alice", "correct horse battery".toCharArray());
    timed.myAlbums(token);
    clock.advance(Duration.ofHours(13));
    assertEquals(UNAUTHENTICATED, assertThrows(DirectoryException.class, () -> timed.myAlbums(token)).reason());
  }

  @Test
  void nonMembersCannotEvenSeeThatAnAlbumExists() {
    String alice = signUp("alice");
    String eve = signUp("eve");
    String album = directory.createAlbum(alice, "holidays", KEY);
    assertReason(NOT_FOUND, () -> directory.album(eve, album));
    assertReason(NOT_FOUND, () -> directory.myWrappedKeys(eve, album));
    assertReason(NOT_FOUND, () -> directory.setCatalogPointer(eve, album, "eve-store:x"));
    assertTrue(directory.myAlbums(eve).isEmpty());
  }

  @Test
  void onlyTheOwnerChangesMembership() {
    String alice = signUp("alice");
    String bob = signUp("bob");
    signUp("carol");
    String album = directory.createAlbum(alice, "holidays", KEY);
    directory.addMember(alice, album, "bob", Map.of(1L, KEY));
    assertReason(FORBIDDEN, () -> directory.addMember(bob, album, "carol", Map.of(1L, KEY)));
    assertReason(FORBIDDEN, () -> directory.removeMember(bob, album, "alice", 2, Map.of("bob", KEY)));
  }

  @Test
  void aNewMemberMustReceiveTheCurrentKey() {
    String alice = signUp("alice");
    signUp("bob");
    String album = directory.createAlbum(alice, "holidays", KEY);
    assertReason(BAD_REQUEST, () -> directory.addMember(alice, album, "bob", Map.of()));
    assertReason(BAD_REQUEST, () -> directory.addMember(alice, album, "bob", Map.of(1L, KEY, 5L, KEY)));
  }

  @Test
  void removalRotatesForExactlyTheRemainingMembers() {
    String alice = signUp("alice");
    String bob = signUp("bob");
    String carol = signUp("carol");
    String album = directory.createAlbum(alice, "holidays", KEY);
    directory.addMember(alice, album, "bob", Map.of(1L, KEY));
    directory.addMember(alice, album, "carol", Map.of(1L, KEY));
    directory.setCatalogPointer(carol, album, "carol-store:catalog");

    // the new key must go to alice and bob, no more and no less
    assertReason(BAD_REQUEST, () -> directory.removeMember(alice, album, "carol", 2, Map.of("alice", KEY)));
    assertReason(BAD_REQUEST, () -> directory.removeMember(alice, album, "carol", 2,
        Map.of("alice", KEY, "bob", KEY, "carol", KEY)));
    // and the epoch must be exactly the next one
    assertReason(CONFLICT, () -> directory.removeMember(alice, album, "carol", 3,
        Map.of("alice", KEY, "bob", KEY)));

    directory.removeMember(alice, album, "carol", 2, Map.of("alice", KEY, "bob", KEY));
    assertEquals(2, directory.album(alice, album).epoch());
    assertEquals(java.util.List.of("alice", "bob"), directory.album(bob, album).members());
    assertTrue(directory.album(alice, album).catalogPointers().isEmpty(), "carol's slice is gone");
    assertEquals(2, directory.myWrappedKeys(bob, album).size());
    assertReason(NOT_FOUND, () -> directory.album(carol, album));
  }

  @Test
  void theOwnerCannotRemoveThemselves() {
    String alice = signUp("alice");
    String album = directory.createAlbum(alice, "holidays", KEY);
    assertReason(BAD_REQUEST, () -> directory.removeMember(alice, album, "alice", 2, Map.of()));
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-11T12:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
