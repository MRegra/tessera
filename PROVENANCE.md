# Provenance

Tessera began as a university *Mobile and Ubiquitous Computing* assignment at IST (the "P2Photo" shared-albums
project, MEIC, 2018–19): an Android app where each member keeps their photos in their own cloud storage and a server
only tracks who is in which album. This repository is a **clean-room Java 21 reimplementation and expansion** written
from an understanding of the problem, not a port of any prior code.

- The original group's Android/Java solution and the course's assignment material are **not** included here — they
  are third-party / course material and stay out of the public tree (kept locally under a git-ignored `reference/`).
- All code in this repository is original work, licensed under [Apache-2.0](LICENSE).
- The semantics Tessera targets are described in our own words in [docs/SPEC.md](docs/SPEC.md).

Tessera also *fixes and finishes* what the original left weak or undone: passwords stored as salted PBKDF2 hashes
instead of plain text, real random session tokens instead of short numeric ones, no cloud-storage credentials on the
server at all, authenticated encryption (AES-GCM) instead of AES's insecure default mode, signed and
rollback-protected catalogs, and **key rotation when a member is removed**, which the original never handled.

Tessera is its own project now: a teaching-first, end-to-end-encrypted shared album.
