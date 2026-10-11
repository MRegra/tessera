# Changelog

All notable changes to Tessera are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Tessera uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-11
### Added
- **Member identities**: RSA-3072 (OAEP/SHA-256) for receiving album keys, Ed25519 for signing; fingerprints for
  out-of-band verification.
- **Album keys** (AES-256-GCM) with epochs, wrapped per member; a new member receives every epoch, so they can read
  the album's history.
- **Signed, encrypted catalogs** per member, rejecting forgery, edits, header swaps, catalogs from other albums and
  rollbacks.
- **Remove + rotate** as one atomic directory operation, with the new key wrapped for exactly the remaining members.
- **Directory server** over HTTP+JSON: PBKDF2 password hashing, random 256-bit sessions with expiry, membership-based
  authorization, non-members can't see that an album exists.
- Storage backends: in-memory and local folder.
- `tessera-cli` demo; 32 tests (crypto, catalogs, directory rules, end-to-end over HTTP).
