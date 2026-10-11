# Tessera

**Shared photo albums where the server never holds a photo — in Java, built to be read.**

[![CI](https://github.com/MRegra/tessera/actions/workflows/ci.yml/badge.svg)](https://github.com/MRegra/tessera/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![status](https://img.shields.io/badge/status-0.1.0%20(pre--1.0)-orange)

A *tessera* is one tile of a mosaic. In Tessera, every member of an album contributes their own tile: the photos
**they** added, kept in **their own** storage (a Dropbox folder, an S3 bucket, a NAS share). The album is the mosaic
— the union of everyone's tiles. A small directory server only knows who is in which album and where each tile lives.

Nobody in the middle can look. Photos and catalogs are encrypted with an album key the server never sees; each
member's catalog is signed, so neither the server nor a storage provider can forge, edit or roll it back; and when a
member is removed the album key **rotates**, so they can't read anything added afterwards. It's a teaching-first
codebase: the goal is that you can read it and learn how end-to-end encrypted sharing actually works.

## 30-second demo

Requires JDK 21 and Maven.

```bash
mvn install                          # build the modules
mvn -q -pl tessera-cli exec:java     # run the demo
```

A real directory server on a random port, three members, three separate "clouds" (folders you can open):

```
alice, bob, carol signed up (keys made on their devices; the server got public keys only).
  bob's fingerprint, as bob sees it:    97b6 cc2e c089 be91 8164
  bob's fingerprint, as alice sees it:  97b6 cc2e c089 be91 8164  (they match ✓)

what carol sees (the union of everyone's signed catalogs):
  tram.jpg     from alice  -> "a yellow tram climbing a hill"
  sunset.jpg   from bob    -> "orange sky over the river"
  pasteis.jpg  from carol  -> "six custard tarts"

what the SERVER knows about the album: members=[alice, bob, carol], epoch=1, 3 catalog pointers. No photos, no names, no keys.
what bob's cloud provider sees: c3905aae4322a9ed3849cab9576fee14f737854c...  (ciphertext ✓)

alice -> removes carol. The album key rotates to epoch 2, wrapped only for alice and bob.
carol -> asks the server for the album: refused (NOT_FOUND) ✓
carol -> goes straight to bob's cloud for night.jpg: can't decrypt it, no epoch-2 key ✓
```

## Who holds what

| | Private keys | Album keys | Photos | Catalogs | Membership |
|---|---|---|---|---|---|
| **A member's device** | their own | in the clear | in the clear | in the clear | yes |
| **Directory server** | — | wrapped (can't open) | — | only *where* they are | yes |
| **A member's storage** | — | — | encrypted | encrypted + signed | — |

## How it works, in one paragraph each

**Identities.** Each member makes two key pairs on their own device: RSA-3072 to *receive* album keys (OAEP padding)
and Ed25519 to *sign* their catalog. Only the public halves go to the server.

**Albums and keys.** An album has a random AES-256 key. Sharing it with a member means encrypting ("wrapping") it with
that member's public key; the server stores the wrapped copies but can't open them.

**Catalogs.** Each member's list of their photos is encrypted with the album key (AES-GCM) and signed with their
Ed25519 key. Readers check, in order: right album and owner, valid signature, not older than a version already seen,
then decrypt.

**Removal.** The owner picks a new album key (epoch + 1), wraps it for exactly the remaining members, and the server
applies "remove + rotate" as one step. New photos and re-sealed catalogs use the new epoch.

The full semantics and the threat model — including what this does **not** protect against — are in
[docs/SPEC.md](docs/SPEC.md). The code is laid out in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Layout

- `tessera-core` — identities, authenticated encryption, album keys, signed catalogs, the `Member` client. JDK only.
  Start with `Member.java`.
- `tessera-server` — the directory service (accounts, sessions, membership, wrapped keys, pointers) over HTTP+JSON,
  and the matching client.
- `tessera-cli` — the demo.

## Status

`0.1.0` — the secure cloud mode. Next: the peer-to-peer mode (members exchange catalogs directly, no server), real
storage adapters, persistence and TLS. See the [roadmap](ROADMAP.md). Where this came from:
[PROVENANCE.md](PROVENANCE.md).

## License

[Apache-2.0](LICENSE)
