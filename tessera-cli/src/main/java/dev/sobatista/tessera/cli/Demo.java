package dev.sobatista.tessera.cli;

import dev.sobatista.tessera.core.Member;
import dev.sobatista.tessera.core.VisiblePhoto;
import dev.sobatista.tessera.core.crypto.CryptoException;
import dev.sobatista.tessera.core.directory.AlbumView;
import dev.sobatista.tessera.core.directory.DirectoryException;
import dev.sobatista.tessera.core.storage.LocalDirectoryBlobStore;
import dev.sobatista.tessera.core.storage.StorageNetwork;
import dev.sobatista.tessera.server.DirectoryService;
import dev.sobatista.tessera.server.HttpDirectory;
import dev.sobatista.tessera.server.TesseraServer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The 30-second tour: a real directory server on a random port, three members, and three separate "clouds" (local
 * folders under a temp directory you can go and look at).
 */
public final class Demo {

  private Demo() {}

  public static void main(String[] args) throws Exception {
    Path clouds = Files.createTempDirectory("tessera-demo-");
    StorageNetwork network = new StorageNetwork();
    try (TesseraServer server = new TesseraServer(new DirectoryService(), 0).start()) {
      HttpDirectory directory = new HttpDirectory(URI.create("http://127.0.0.1:" + server.port()));
      say("tessera — shared albums where the server never holds a photo");
      say("directory server on :" + server.port() + "; each member's cloud is a folder under " + clouds);
      say("");

      Member alice = signUp("alice", directory, clouds, network);
      Member bob = signUp("bob", directory, clouds, network);
      Member carol = signUp("carol", directory, clouds, network);
      say("alice, bob, carol signed up (keys made on their devices; the server got public keys only).");
      say("  bob's fingerprint, as bob sees it:    " + bob.fingerprint());
      say("  bob's fingerprint, as alice sees it:  " + alice.fingerprintOf("bob") + "  (they match ✓)");
      say("");

      String album = alice.createAlbum("Lisbon 2026");
      alice.invite(album, "bob");
      alice.invite(album, "carol");
      say("alice -> creates 'Lisbon 2026', invites bob and carol (album key wrapped for each of them).");

      alice.addPhoto(album, "tram.jpg", bytes("a yellow tram climbing a hill"));
      bob.addPhoto(album, "sunset.jpg", bytes("orange sky over the river"));
      carol.addPhoto(album, "pasteis.jpg", bytes("six custard tarts"));
      say("each member adds a photo — to their OWN cloud, encrypted.");
      say("");

      say("what carol sees (the union of everyone's signed catalogs):");
      for (VisiblePhoto p : carol.view(album)) {
        say("  %-12s from %-5s  -> \"%s\"", p.entry().name(), p.owner(), text(carol.open(p)));
      }
      say("");

      AlbumView known = directory.album(loginAs(directory, "alice"), album);
      say("what the SERVER knows about the album: members=" + known.members() + ", epoch=" + known.epoch()
          + ", " + known.catalogPointers().size() + " catalog pointers. No photos, no names, no keys.");
      Path bobsPhoto = Files.walk(clouds.resolve("bob")).filter(Files::isRegularFile)
          .filter(f -> f.toString().contains("/photos/")).findFirst().orElseThrow();
      say("what bob's cloud provider sees in " + clouds.relativize(bobsPhoto) + ":");
      say("  " + hex(Files.readAllBytes(bobsPhoto), 24) + "...  (ciphertext ✓)");
      say("");

      alice.remove(album, "carol");
      say("alice -> removes carol. The album key rotates to epoch 2, wrapped only for alice and bob.");
      bob.addPhoto(album, "night.jpg", bytes("fado bar, 2am"));
      say("bob   -> adds night.jpg (encrypted under epoch 2).");
      try {
        carol.view(album);
        say("carol -> can still view the album ✗ (this should not happen)");
      } catch (DirectoryException e) {
        say("carol -> asks the server for the album: refused (" + e.reason() + ") ✓");
      }
      VisiblePhoto night = bob.view(album).stream().filter(p -> p.entry().name().equals("night.jpg"))
          .findFirst().orElseThrow();
      try {
        carol.open(night);
        say("carol -> decrypts night.jpg ✗ (this should not happen)");
      } catch (CryptoException e) {
        say("carol -> goes straight to bob's cloud for night.jpg: can't decrypt it, no epoch-2 key ✓");
      }
      say("alice -> still sees " + names(alice.view(album)) + " ✓");
      say("");
      say("nothing was stored on the server that could open a single photo.");
    }
  }

  private static Member signUp(String user, HttpDirectory directory, Path clouds, StorageNetwork network) {
    return Member.signUp(user, (user + "-demo-password").toCharArray(), directory,
        new LocalDirectoryBlobStore(user + "-cloud", clouds.resolve(user)), network);
  }

  private static String loginAs(HttpDirectory directory, String user) {
    return directory.login(user, (user + "-demo-password").toCharArray());
  }

  private static String names(List<VisiblePhoto> photos) {
    return photos.stream().map(p -> p.entry().name()).collect(Collectors.joining(", ", "[", "]"));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }

  private static String hex(byte[] data, int n) {
    return Stream.iterate(0, i -> i + 1).limit(Math.min(n, data.length))
        .map(i -> String.format("%02x", data[i])).collect(Collectors.joining());
  }

  private static void say(String format, Object... args) {
    System.out.println(args.length == 0 ? format : String.format(format, args));
  }
}
