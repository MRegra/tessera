package dev.sobatista.tessera.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sobatista.tessera.core.crypto.PublicIdentity;
import dev.sobatista.tessera.core.directory.AlbumView;
import dev.sobatista.tessera.core.directory.Directory;
import dev.sobatista.tessera.core.directory.DirectoryException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A {@link Directory} that talks to a {@link TesseraServer}. The client side of the wire. */
public final class HttpDirectory implements Directory {

  private final URI base;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  public HttpDirectory(URI base) {
    this.base = base;
  }

  @Override
  public void register(String userId, char[] password, PublicIdentity identity) {
    call("POST", "/v1/register", null, new Wire.RegisterRequest(userId, new String(password),
        identity.encodedEncryptionKey(), identity.encodedSigningKey()), null);
  }

  @Override
  public String login(String userId, char[] password) {
    return call("POST", "/v1/login", null, new Wire.LoginRequest(userId, new String(password)),
        new TypeReference<Wire.LoginResponse>() {}).token();
  }

  @Override
  public PublicIdentity publicIdentity(String token, String userId) {
    return call("GET", "/v1/users/" + enc(userId) + "/identity", token, null,
        new TypeReference<Wire.IdentityResponse>() {}).toIdentity();
  }

  @Override
  public String createAlbum(String token, String name, byte[] ownerWrappedKey) {
    return call("POST", "/v1/albums", token, new Wire.CreateAlbumRequest(name, ownerWrappedKey),
        new TypeReference<Wire.CreateAlbumResponse>() {}).albumId();
  }

  @Override
  public AlbumView album(String token, String albumId) {
    return fromWire(call("GET", "/v1/albums/" + enc(albumId), token, null,
        new TypeReference<Wire.AlbumResponse>() {}));
  }

  @Override
  public List<AlbumView> myAlbums(String token) {
    return call("GET", "/v1/albums", token, null, new TypeReference<List<Wire.AlbumResponse>>() {})
        .stream().map(HttpDirectory::fromWire).toList();
  }

  @Override
  public void addMember(String token, String albumId, String userId, Map<Long, byte[]> wrappedKeysByEpoch) {
    call("POST", "/v1/albums/" + enc(albumId) + "/members", token,
        new Wire.AddMemberRequest(userId, wrappedKeysByEpoch), null);
  }

  @Override
  public void removeMember(String token, String albumId, String userId, long newEpoch,
      Map<String, byte[]> newWrappedKeys) {
    call("POST", "/v1/albums/" + enc(albumId) + "/members/" + enc(userId) + "/remove", token,
        new Wire.RemoveMemberRequest(newEpoch, newWrappedKeys), null);
  }

  @Override
  public Map<Long, byte[]> myWrappedKeys(String token, String albumId) {
    return call("GET", "/v1/albums/" + enc(albumId) + "/keys", token, null,
        new TypeReference<Wire.KeysResponse>() {}).wrappedKeysByEpoch();
  }

  @Override
  public void setCatalogPointer(String token, String albumId, String pointer) {
    call("PUT", "/v1/albums/" + enc(albumId) + "/catalog-pointer", token, new Wire.PointerRequest(pointer), null);
  }

  private <T> T call(String method, String path, String token, Object body, TypeReference<T> responseType) {
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
          .header("Content-Type", "application/json");
      if (token != null) {
        request.header("Authorization", "Bearer " + token);
      }
      HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() >= 400) {
        Wire.ErrorResponse error = json.readValue(response.body(), Wire.ErrorResponse.class);
        DirectoryException.Reason reason;
        try {
          reason = DirectoryException.Reason.valueOf(error.error());
        } catch (IllegalArgumentException e) {
          reason = DirectoryException.Reason.BAD_REQUEST;
        }
        throw new DirectoryException(reason, error.message());
      }
      return responseType == null ? null : json.readValue(response.body(), responseType);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot reach the directory at " + base, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private static AlbumView fromWire(Wire.AlbumResponse r) {
    return new AlbumView(r.albumId(), r.name(), r.owner(), r.epoch(), r.members(), r.catalogPointers());
  }

  private static String enc(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8);
  }
}
