---
title: "I rebuilt another college project. This one is a photo album the server can't look into."
subtitle: "Why 'encrypted' isn't enough, what wrapping a key means, and what actually happens when you kick someone out of a shared album. (Tessera, part 1.)"
tags: [cryptography, java, end-to-end-encryption, security, distributed-systems]
status: draft
date: 2026-10-11
series: Tessera
part: 1
---

For a mobile computing class at university, my group built a shared photo album app. The twist was where the photos
lived: not on our server, but in each person's **own** cloud storage. Our server only knew who was in which album and
where each person's photos were. It worked well enough to pass, and then it sat in a repo.

I dug it back up, threw the old code away, and rebuilt it in Java from scratch — because the best way I know to
understand a thing is to build it and then explain it. And this time, the security had to be real. Reading the old
code back, it wasn't: passwords stored as plain text, every user's cloud-storage token sitting on our server, and a
"remove from album" that removed you from a list and nothing else.

The new project is called **Tessera** — a tessera is one tile of a mosaic. Every member contributes their own tile
(their photos, in their storage), and the album is the mosaic.

## Who should be able to see the photos?

Exactly the people in the album. Not the server. Not Dropbox. Not whoever's on the same Wi-Fi. And — the part
everyone forgets — not someone who *used to be* in the album.

That rules out the obvious design, where the server stores the photos and checks who's allowed to see them. The
moment the server can check, it can also look. So in Tessera, the server never holds a photo, and the photos it can
point you to are encrypted with a key it doesn't have.

## The album key, and "wrapping" it

Every album has one random AES-256 key. Everything in the album is encrypted with it. The question is how members
get it without the server seeing it.

Each member has a key pair. Sharing the album key with Bob means encrypting it with **Bob's public key** — that's
called *wrapping* it. The server stores the wrapped copy and hands it to Bob, but only Bob's private key, which never
left his phone, can unwrap it. The server is a postman carrying a locked box it has no key to.

## "Encrypted" isn't enough: sign it, too

Each member keeps a **catalog**: a little file listing the photos they added. Encrypting it hides it from Dropbox.
But hiding isn't the same as trusting. Whoever hosts Bob's storage could swap his catalog for an older one (hiding his
newest photos) or for someone else's entirely.

So each member also **signs** their catalog with a second key pair (Ed25519), and the signature covers a header that
says which album, whose catalog, which key, and which version. When Alice reads Bob's catalog she checks all of it,
in order: right album and owner, valid signature, not older than a version she's already seen — and only then
decrypts. The code for that is about 30 lines, and it's the most important file in the project
(`SealedCatalog.java`).

## Kicking someone out

Here's the one the original never handled. Carol was in the album; now she isn't. Removing her name from the
server's list does almost nothing: she still has the album key. Anything added afterwards, encrypted with that same
key and sitting in shared-link storage, is still hers to read.

The fix is **rotation**. When Alice removes Carol, she makes a brand-new album key (epoch 2), wraps it for everyone
*except* Carol, and the server applies "remove Carol + switch to epoch 2" as a single step. From then on, new photos
and re-sealed catalogs use epoch 2. Carol can still read what she already could — no system can make someone forget
— but nothing new.

In the demo you can watch it happen: Carol tries to fetch Bob's new photo straight from his storage, bypassing the
server entirely, and gets a pile of bytes she can't decrypt.

## Try it

```bash
mvn install
mvn -q -pl tessera-cli exec:java
```

Next time: the honest limits. The server can't read your photos — but there's one thing a malicious server *can*
still do, and it's the same problem every end-to-end encrypted messenger has to solve.
