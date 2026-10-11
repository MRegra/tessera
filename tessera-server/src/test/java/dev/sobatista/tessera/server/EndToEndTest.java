package dev.sobatista.tessera.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sobatista.tessera.core.Member;
import dev.sobatista.tessera.core.VisiblePhoto;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.directory.Directory;
import dev.sobatista.tessera.core.directory.DirectoryException;
import dev.sobatista.tessera.core.storage.InMemoryBlobStore;
import dev.sobatista.tessera.core.storage.StorageNetwork;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The whole system over real HTTP: a server, three members, three separate storages. */
class EndToEndTest {

  private TesseraServer server;
  private Directory directory;
  private final StorageNetwork network = new StorageNetwork();
  private final InMemoryBlobStore bobStore = new InMemoryBlobStore("bob-cloud");
  private Member alice;
  private Member bob;
  private Member carol;

  @BeforeEach
  void start() throws Exception {
    server = new TesseraServer(DirectoryService.forTests(), 0).start();
    directory = new HttpDirectory(URI.create("http://127.0.0.1:" + server.port()));
    alice = Member.signUp("alice", "alice's long password".toCharArray(), directory,
        new InMemoryBlobStore("alice-cloud"), network);
    bob = Member.signUp("bob", "bob's long password!".toCharArray(), directory, bobStore, network);
    carol = Member.signUp("carol", "carol's long password".toCharArray(), directory,
        new InMemoryBlobStore("carol-cloud"), network);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private static byte[] photo(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static Set<String> names(List<VisiblePhoto> photos) {
    return photos.stream().map(p -> p.entry().name()).collect(Collectors.toSet());
  }

  @Test
  void everyMemberSeesEveryonesSliceAndCanOpenIt() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.invite(album, "bob");
    alice.invite(album, "carol");
    alice.addPhoto(album, "tram.jpg", photo("yellow tram"));
    bob.addPhoto(album, "sunset.jpg", photo("orange sky"));
    carol.addPhoto(album, "pasteis.jpg", photo("custard"));

    for (Member m : List.of(alice, bob, carol)) {
      List<VisiblePhoto> seen = m.view(album);
      assertEquals(Set.of("tram.jpg", "sunset.jpg", "pasteis.jpg"), names(seen), m.userId());
    }
    VisiblePhoto sunset = carol.view(album).stream().filter(p -> p.entry().name().equals("sunset.jpg"))
        .findFirst().orElseThrow();
    assertArrayEquals(photo("orange sky"), carol.open(sunset));
  }

  @Test
  void aLateMemberCanReadTheWholeHistory() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.addPhoto(album, "before-bob.jpg", photo("early"));
    alice.invite(album, "bob");
    VisiblePhoto early = bob.view(album).get(0);
    assertArrayEquals(photo("early"), bob.open(early));
  }

  @Test
  void aRemovedMemberLosesEverythingNew() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.invite(album, "bob");
    alice.invite(album, "carol");
    bob.addPhoto(album, "old.jpg", photo("before the removal"));
    carol.view(album); // carol has seen the album; she holds epoch 1

    alice.remove(album, "carol");
    bob.addPhoto(album, "new.jpg", photo("after the removal"));

    // the server shuts carol out...
    assertThrows(DirectoryException.class, () -> carol.view(album));
    // ...and even reading bob's storage directly doesn't help: his catalog is now sealed under epoch 2
    VisiblePhoto newOne = alice.view(album).stream().filter(p -> p.entry().name().equals("new.jpg"))
        .findFirst().orElseThrow();
    assertEquals(2, newOne.entry().epoch());
    assertThrows(CryptoException.class, () -> carol.open(newOne));

    // the remaining members carry on normally, old and new photos alike
    assertEquals(Set.of("old.jpg", "new.jpg"), names(bob.view(album)));
    assertTrue(alice.view(album).stream().allMatch(p -> alice.open(p).length > 0));
  }

  @Test
  void aStorageHostCannotSlipInAFakeCatalog() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.invite(album, "bob");
    bob.addPhoto(album, "real.jpg", photo("real"));
    // bob's storage provider (or anyone who controls it) replaces his catalog with alice's
    String catalogBlob = "tessera/" + album + "/catalog";
    alice.addPhoto(album, "alices.jpg", photo("alice's"));
    byte[] alicesCatalog = network.fetch("alice-cloud:" + catalogBlob).orElseThrow();
    bobStore.put(catalogBlob, alicesCatalog);
    assertThrows(CryptoException.class, () -> alice.view(album));
  }

  @Test
  void aStorageHostCannotRollBackACatalog() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.invite(album, "bob");
    bob.addPhoto(album, "first.jpg", photo("1"));
    String catalogBlob = "tessera/" + album + "/catalog";
    byte[] v1 = bobStore.get(catalogBlob).orElseThrow();
    bob.addPhoto(album, "second.jpg", photo("2"));
    assertEquals(2, alice.view(album).size()); // alice has now seen version 2
    bobStore.put(catalogBlob, v1); // replay the old version
    assertThrows(CryptoException.class, () -> alice.view(album));
  }

  @Test
  void aTamperedPhotoIsRefused() {
    String album = alice.createAlbum("Lisbon 2026");
    alice.invite(album, "bob");
    bob.addPhoto(album, "sunset.jpg", photo("orange sky"));
    VisiblePhoto sunset = alice.view(album).get(0);
    byte[] blob = bobStore.get(sunset.entry().blobName()).orElseThrow();
    blob[blob.length - 1] ^= 1;
    bobStore.put(sunset.entry().blobName(), blob);
    assertThrows(CryptoException.class, () -> alice.open(sunset));
  }

  @Test
  void fingerprintsMatchWhatEachMemberHolds() {
    assertEquals(bob.fingerprint(), alice.fingerprintOf("bob"));
  }
}
