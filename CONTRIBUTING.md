# Contributing to Tessera

Thanks for looking! Tessera is a teaching-first project about end-to-end encrypted sharing, so clear code and clear
explanations both count as contributions.

## Build & test
Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn verify        # build + run all tests
mvn install       # build the modules into ~/.m2 (needed before exec:java below)
```

Run the demo (a real server on a random port, three members, three local "clouds"):

```bash
mvn -q -pl tessera-cli exec:java
```

## How it's laid out
- `tessera-core` — identities, encryption, album keys, signed catalogs, and the `Member` client. Start here.
- `tessera-server` — the directory: accounts, sessions, membership and rotation rules, over HTTP+JSON.
- `tessera-cli` — the demo.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a reading order.

## Sending a change
1. Branch from `main`.
2. Keep the change small and focused. Add a test — a bug fix gets a test that fails without it; a security property
   gets a test that tries to break it.
3. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`; `feat!:` or a `BREAKING CHANGE:` footer for anything that breaks the public API.
4. `mvn verify` is green.
5. Open a PR describing what and why.

By contributing you agree your work is licensed under the project's [Apache-2.0](LICENSE) license.

## Style
Readable over clever. This code is also documentation — someone should be able to learn the protocol by reading it.
Comments explain *why*, not *what*. Anything that signs, encrypts or verifies spells out exactly which bytes it covers.
