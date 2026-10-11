package dev.sobatista.tessera.server;

import dev.sobatista.tessera.core.crypto.PublicIdentity;
import java.util.List;
import java.util.Map;

/**
 * The JSON shapes on the wire. {@code byte[]} fields travel as base64 (Jackson's default). These records are the
 * whole API contract between {@link TesseraServer} and {@link HttpDirectory}; see docs/SPEC.md for the endpoints.
 */
final class Wire {

  private Wire() {}

  record RegisterRequest(String userId, String password, byte[] encryptionKey, byte[] signingKey) {}

  record LoginRequest(String userId, String password) {}

  record LoginResponse(String token) {}

  record IdentityResponse(String userId, byte[] encryptionKey, byte[] signingKey) {
    static IdentityResponse of(PublicIdentity identity) {
      return new IdentityResponse(identity.userId(), identity.encodedEncryptionKey(), identity.encodedSigningKey());
    }

    PublicIdentity toIdentity() {
      return PublicIdentity.decode(userId, encryptionKey, signingKey);
    }
  }

  record CreateAlbumRequest(String name, byte[] ownerWrappedKey) {}

  record CreateAlbumResponse(String albumId) {}

  record AlbumResponse(String albumId, String name, String owner, long epoch, List<String> members,
      Map<String, String> catalogPointers) {}

  record AddMemberRequest(String userId, Map<Long, byte[]> wrappedKeysByEpoch) {}

  record RemoveMemberRequest(long newEpoch, Map<String, byte[]> newWrappedKeys) {}

  record KeysResponse(Map<Long, byte[]> wrappedKeysByEpoch) {}

  record PointerRequest(String pointer) {}

  record ErrorResponse(String error, String message) {}
}
