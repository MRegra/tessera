# Architecture

Three modules, each depending only on the ones before it.

```
tessera-core      JDK only
  crypto/           Aead (AES-256-GCM), Identity + PublicIdentity (RSA-OAEP wrap, Ed25519 sign), CryptoException
  album/            AlbumKey, AlbumKeyring, Catalog, PhotoEntry, SealedCatalog, Codec (canonical encoding)
  storage/          BlobStore, InMemoryBlobStore, LocalDirectoryBlobStore, StorageNetwork
  directory/        Directory (the server's interface), AlbumView, DirectoryException
  Member            the client: every plaintext lives here and only here

tessera-server    + Jackson
  DirectoryService  the rules (accounts, sessions, membership, rotation), transport-agnostic, in memory
  Passwords         PBKDF2-HMAC-SHA256
  TesseraServer     DirectoryService over HTTP+JSON (JDK HttpServer)
  HttpDirectory     the client side of the same API (implements Directory)
  Wire              the JSON shapes

tessera-cli
  Demo              the 30-second tour
```

## Reading order

1. `crypto/Aead.java` — what "authenticated" encryption buys you.
2. `crypto/Identity.java`, `PublicIdentity.java` — wrapping keys and signing, and the fingerprint caveat.
3. `album/SealedCatalog.java` — the most important file: what's signed, what's encrypted, and the order of checks.
4. `Member.java` — the protocol from one member's point of view.
5. `server/DirectoryService.java` — what the server enforces, and what it never gets to see.

## Design choices

- **The server is an interface.** `Directory` is defined in core; the client logic never knows whether it's talking
  to `DirectoryService` in-process (tests) or over HTTP (`HttpDirectory`). That keeps the protocol testable without a
  network and the transport swappable.
- **Storage is untrusted by construction.** `StorageNetwork` hands out read access to anyone, on purpose: privacy comes
  from the album key, not from storage permissions.
- **Canonical encoding for anything signed.** `Codec` is fixed-order, length-prefixed bytes. JSON is used only on the
  wire to the directory, where nothing is signed.
- **One key, one job.** RSA for receiving album keys, Ed25519 for signing catalogs.
- **Fail loudly.** Every failed check throws `CryptoException`; a bad catalog is never skipped or partially trusted.
