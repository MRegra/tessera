# Security Policy

## Supported versions
Tessera is pre-1.0; only the latest `0.x` release gets fixes.

## Reporting a vulnerability
Please **do not** open a public issue for a vulnerability. Email **sobatistacyber@gmail.com** with details and a
proof of concept if you have one. You'll get an acknowledgement within a few days.

## Scope and threat model
Tessera is a **teaching** implementation of end-to-end encrypted shared albums. Its cryptographic design is the
interesting part and is taken seriously: the directory and storage hosts never see plaintext, catalogs are signed and
rollback-protected, and removed members lose access to new content. The full threat model, including what it does
**not** protect against, is in [docs/SPEC.md](docs/SPEC.md#threat-model).

Known limits of `0.1.0`, by design for the demo: the directory speaks **plain HTTP** (deploy it behind TLS), keeps its
state **in memory**, and members learn each other's public keys from the directory (compare fingerprints out of
band). These are on the [roadmap](ROADMAP.md).
