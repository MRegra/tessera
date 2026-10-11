# Tessera specification (v0.1)

This document describes what Tessera does and guarantees, in our own words. The code follows it; where they
disagree, that's a bug.

## Parties

- **Members** run the client (`Member`). Each has a private identity and their own **storage**.
- The **directory** is a server that knows accounts, album membership, the current key epoch of each album, the album
  key *wrapped* for each member, and a **catalog pointer** per member per album.
- **Storage** is any place a member can write blobs and others can read them by name ("a shared link"). Storage is
  untrusted: its host can read, change, delete or replay anything in it.

## Data

- **Identity**: an RSA-3072 key pair (key wrapping, OAEP with SHA-256 and MGF1-SHA-256) and an Ed25519 key pair
  (signing). The public halves plus the user id form the *public identity*; its **fingerprint** is the first 10
  bytes of SHA-256(userId ‖ encryptionKey ‖ signingKey), shown as hex.
- **Album key**: a 256-bit AES key with an **epoch** (≥ 1). Epoch 1 is created with the album; each removal creates
  the next one.
- **Photo**: encrypted with AES-256-GCM under the album key of the current epoch, with associated data
  `tessera-photo-v1|albumId|owner|photoId|epoch`. Stored in the owner's storage at
  `tessera/<albumId>/photos/<photoId>`.
- **Catalog**: `(albumId, owner, version, photos[])`, each photo entry being
  `(photoId, name, blobName, epoch, sha256(plaintext), size)`. `version` increases by one on every change.
- **Sealed catalog**: `header ‖ ciphertext ‖ signature` where
  - `header = encode("tessera-catalog-v1", albumId, owner, epoch, version)`
  - `ciphertext = AES-GCM(albumKey[epoch], encode(catalog), aad = header)`
  - `signature = Ed25519(owner, encode(header, ciphertext))`

  Encodings are length-prefixed byte strings and big-endian 64-bit integers, in a fixed order, so every party
  derives the same bytes. Stored in the owner's storage at `tessera/<albumId>/catalog`.

## Operations

- **Sign up**: the member generates their identity locally, registers (user id, password, public identity), logs in.
  Passwords are ≥ 10 characters; the directory stores PBKDF2-HMAC-SHA256 (600,000 iterations, 16-byte salt).
- **Log in**: returns a random 256-bit session token, valid 12 hours. A wrong password and an unknown user produce the
  same error and take the same time.
- **Create album**: the client generates the epoch-1 key and sends it wrapped for itself. The caller is the owner and
  the first member.
- **Invite** (owner): the owner wraps **every epoch** it holds for the new member, so they can read the album's whole
  history. The directory requires the current epoch to be among them.
- **Add photo**: encrypt with the current key, store in own storage, add an entry to own catalog, re-seal, store, and
  point the directory at it.
- **View**: for every current member with a pointer, fetch their sealed catalog and open it (checks below). The album
  is the union of all opened catalogs. A catalog that fails a check is an error, never silently skipped.
- **Open photo**: fetch from the owner's storage, decrypt with the key for the entry's epoch and the same associated
  data, check SHA-256 against the catalog entry.
- **Remove** (owner): generate the next epoch's key, wrap it for exactly the remaining members, and send
  `remove(user, newEpoch, wrappedKeys)`. The directory applies it atomically and only if `newEpoch = epoch + 1` and
  the wrapped keys cover exactly the remaining members. The removed member's pointer and wrapped keys are deleted. The
  owner re-seals their own catalog under the new epoch; every other member re-seals theirs the next time they view or
  add to the album.

### Opening a sealed catalog

1. The header's album id and owner are the ones requested.
2. The signature verifies under the owner's public signing key (as given by the directory).
3. `version ≥` the highest version this reader has already accepted for that owner and album.
4. Decrypt with the key for the header's epoch; the decrypted catalog's album, owner and version equal the header's.

## Authorization (enforced by the directory)

- Every operation except sign-up and log-in needs a valid session.
- Only members can read an album, its keys, or set their own pointer. To a non-member, an album doesn't exist
  (`NOT_FOUND`, not `FORBIDDEN`).
- Only the owner can add or remove members. The owner cannot remove themselves.
- A member can only set their own pointer.

## Threat model

**Protected against:**
- A curious or compromised **directory**: it never sees photos, catalog contents, photo names or album keys.
- A curious or malicious **storage host**: it sees only ciphertext; forging, editing, swapping or rolling back a
  catalog is detected; editing a photo is detected.
- A **removed member**: cannot obtain keys for later epochs, so cannot read photos added or catalogs re-sealed after
  their removal, even by reading other members' storage directly.
- **Network eavesdroppers on storage**: storage only ever carries ciphertext.

**Not protected against (stated plainly):**
- **A malicious directory substituting public keys.** Members learn each other's public keys from the directory. A
  malicious one could hand out its own key, and the next album key wrapped "for" that member would be readable by it.
  Defense: compare fingerprints out of band (in person, over a call). Automatic key transparency is future work.
- **What a removed member already had.** They keep anything they downloaded or could decrypt while a member,
  including the keys of the epochs they held. Rotation protects the future, not the past.
- **Metadata.** The directory knows who is in which album and when catalogs change; storage hosts see sizes and
  timing.
- **A dishonest member.** A member can share what they can read with anyone. Encryption can't stop that.
- **The transport of the demo.** The directory speaks plain HTTP so the demo has no setup; session tokens and
  passwords cross it in the clear. Deploy it behind TLS.
- **Persistence and availability.** The v0.1 directory keeps state in memory; restart it and accounts are gone. A
  member's storage being unavailable makes their photos unavailable.
