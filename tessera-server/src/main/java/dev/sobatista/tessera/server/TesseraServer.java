package dev.sobatista.tessera.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sobatista.tessera.core.directory.AlbumView;
import dev.sobatista.tessera.core.directory.DirectoryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directory over HTTP+JSON, on the JDK's built-in {@code com.sun.net.httpserver} — no web framework to learn
 * before you can read the routing.
 *
 * <p>Endpoints (all under {@code /v1}, authenticated ones take {@code Authorization: Bearer <token>}):
 * <pre>
 *   POST /register                              create an account (public keys only, never private ones)
 *   POST /login                                 -> {token}
 *   GET  /users/{id}/identity                   a member's public keys
 *   POST /albums                                create an album (owner's wrapped key for epoch 1)
 *   GET  /albums                                albums I'm a member of
 *   GET  /albums/{id}                           members, epoch, catalog pointers
 *   POST /albums/{id}/members                   owner: add a member with their wrapped keys
 *   POST /albums/{id}/members/{user}/remove     owner: remove + rotate to the next epoch
 *   GET  /albums/{id}/keys                      my wrapped keys, by epoch
 *   PUT  /albums/{id}/catalog-pointer           where my catalog lives
 * </pre>
 *
 * <p>Plain HTTP on purpose for the demo; in any real deployment put it behind TLS (a reverse proxy is enough).
 * Everything sensitive is already end-to-end encrypted, but session tokens and passwords are not.
 */
public final class TesseraServer implements AutoCloseable {

  private static final int MAX_BODY = 1 << 20;
  private static final Pattern USER_IDENTITY = Pattern.compile("/v1/users/([^/]+)/identity");
  private static final Pattern ALBUM = Pattern.compile("/v1/albums/([^/]+)");
  private static final Pattern MEMBERS = Pattern.compile("/v1/albums/([^/]+)/members");
  private static final Pattern REMOVE = Pattern.compile("/v1/albums/([^/]+)/members/([^/]+)/remove");
  private static final Pattern KEYS = Pattern.compile("/v1/albums/([^/]+)/keys");
  private static final Pattern POINTER = Pattern.compile("/v1/albums/([^/]+)/catalog-pointer");

  private final DirectoryService directory;
  private final HttpServer http;
  private final ExecutorService executor = Executors.newFixedThreadPool(8);
  private final ObjectMapper json = new ObjectMapper();

  public TesseraServer(DirectoryService directory, int port) throws IOException {
    this.directory = directory;
    this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    http.createContext("/v1/", this::handle);
    http.setExecutor(executor);
  }

  public TesseraServer start() {
    http.start();
    return this;
  }

  public int port() {
    return http.getAddress().getPort();
  }

  @Override
  public void close() {
    http.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      try {
        Object response = route(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange);
        send(exchange, response == null ? 204 : 200, response);
      } catch (DirectoryException e) {
        send(exchange, status(e.reason()), new Wire.ErrorResponse(e.reason().name(), e.getMessage()));
      } catch (IllegalArgumentException | IOException e) {
        send(exchange, 400, new Wire.ErrorResponse("BAD_REQUEST", "malformed request"));
      } catch (RuntimeException e) {
        send(exchange, 500, new Wire.ErrorResponse("INTERNAL", "internal error"));
      }
    }
  }

  private Object route(String method, String path, HttpExchange exchange) throws IOException {
    Matcher m;
    if (method.equals("POST") && path.equals("/v1/register")) {
      Wire.RegisterRequest r = read(exchange, Wire.RegisterRequest.class);
      directory.register(r.userId(), chars(r.password()),
          dev.sobatista.tessera.core.crypto.PublicIdentity.decode(r.userId(), r.encryptionKey(), r.signingKey()));
      return null;
    }
    if (method.equals("POST") && path.equals("/v1/login")) {
      Wire.LoginRequest r = read(exchange, Wire.LoginRequest.class);
      return new Wire.LoginResponse(directory.login(r.userId(), chars(r.password())));
    }
    String token = bearer(exchange);
    if (method.equals("GET") && (m = USER_IDENTITY.matcher(path)).matches()) {
      return Wire.IdentityResponse.of(directory.publicIdentity(token, m.group(1)));
    }
    if (method.equals("POST") && path.equals("/v1/albums")) {
      Wire.CreateAlbumRequest r = read(exchange, Wire.CreateAlbumRequest.class);
      return new Wire.CreateAlbumResponse(directory.createAlbum(token, r.name(), r.ownerWrappedKey()));
    }
    if (method.equals("GET") && path.equals("/v1/albums")) {
      return directory.myAlbums(token).stream().map(TesseraServer::toWire).toList();
    }
    if (method.equals("POST") && (m = REMOVE.matcher(path)).matches()) {
      Wire.RemoveMemberRequest r = read(exchange, Wire.RemoveMemberRequest.class);
      directory.removeMember(token, m.group(1), m.group(2), r.newEpoch(), r.newWrappedKeys());
      return null;
    }
    if (method.equals("POST") && (m = MEMBERS.matcher(path)).matches()) {
      Wire.AddMemberRequest r = read(exchange, Wire.AddMemberRequest.class);
      directory.addMember(token, m.group(1), r.userId(), r.wrappedKeysByEpoch());
      return null;
    }
    if (method.equals("GET") && (m = KEYS.matcher(path)).matches()) {
      return new Wire.KeysResponse(directory.myWrappedKeys(token, m.group(1)));
    }
    if (method.equals("PUT") && (m = POINTER.matcher(path)).matches()) {
      directory.setCatalogPointer(token, m.group(1), read(exchange, Wire.PointerRequest.class).pointer());
      return null;
    }
    if (method.equals("GET") && (m = ALBUM.matcher(path)).matches()) {
      return toWire(directory.album(token, m.group(1)));
    }
    throw new DirectoryException(DirectoryException.Reason.NOT_FOUND, "no such endpoint: " + method + " " + path);
  }

  private static Wire.AlbumResponse toWire(AlbumView v) {
    return new Wire.AlbumResponse(v.albumId(), v.name(), v.owner(), v.epoch(), v.members(), v.catalogPointers());
  }

  private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      byte[] body = in.readNBytes(MAX_BODY + 1);
      if (body.length > MAX_BODY) {
        throw new DirectoryException(DirectoryException.Reason.BAD_REQUEST, "request body too large");
      }
      return json.readValue(body, type);
    }
  }

  private static String bearer(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
  }

  private static char[] chars(String password) {
    return password == null ? null : password.toCharArray();
  }

  private void send(HttpExchange exchange, int status, Object body) throws IOException {
    if (body == null) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static int status(DirectoryException.Reason reason) {
    return switch (reason) {
      case BAD_REQUEST -> 400;
      case UNAUTHENTICATED -> 401;
      case FORBIDDEN -> 403;
      case NOT_FOUND -> 404;
      case CONFLICT -> 409;
    };
  }

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8640;
    TesseraServer server = new TesseraServer(new DirectoryService(), port).start();
    System.out.println("tessera directory listening on http://127.0.0.1:" + server.port() + "/v1/");
  }
}
