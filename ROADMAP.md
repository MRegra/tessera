# Roadmap

Tessera is built in layers so each milestone is a self-contained thing to learn and to write about.

## Shipped in 0.1.0
- **The secure cloud mode**: member identities (RSA-OAEP key wrapping, Ed25519 signing), AES-256-GCM album keys
  wrapped per member, per-member catalogs that are encrypted, signed and rollback-protected, photos verified by hash.
- **Key rotation on removal**: remove + rotate as one atomic directory operation; removed members lose access to
  everything new.
- A directory server that never sees content: salted PBKDF2 passwords, random 256-bit sessions, membership-based
  authorization. 32 tests and a runnable demo.

## Next
- **0.2 — peer-to-peer mode**: members near each other exchange catalogs and photos directly (the original project's
  Wi-Fi Direct mode), with the same encryption and signatures, over a simulated link first.
- **Real storage adapters**: S3-compatible and WebDAV, with credentials kept on the member's device only.
- **Persistence and TLS** for the directory.
- **Key transparency**: make public-key substitution by a malicious directory detectable automatically, not only by
  comparing fingerprints.
- An **Android client** on top of `tessera-core`.
