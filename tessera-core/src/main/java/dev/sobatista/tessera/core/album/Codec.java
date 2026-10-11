package dev.sobatista.tessera.core.album;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A tiny, explicit binary encoding: big-endian integers and length-prefixed byte strings.
 *
 * <p>Why not JSON? Anything that gets <em>signed</em> needs a canonical form — the exact same bytes on every machine.
 * JSON has many spellings of the same value (key order, whitespace, number formats), so two honest parties can
 * disagree about what was signed. A fixed field order with length prefixes has exactly one encoding.
 */
final class Codec {

  private Codec() {}

  static final class Writer {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    Writer string(String value) {
      return bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    Writer bytes(byte[] value) {
      try {
        out.writeInt(value.length);
        out.write(value);
        return this;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    Writer longValue(long value) {
      try {
        out.writeLong(value);
        return this;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    byte[] toByteArray() {
      return bytes.toByteArray();
    }
  }

  static final class Reader {
    private final DataInputStream in;

    Reader(byte[] data) {
      this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    String string() {
      return new String(bytes(), StandardCharsets.UTF_8);
    }

    byte[] bytes() {
      try {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
          throw new IllegalArgumentException("malformed field length " + length);
        }
        return in.readNBytes(length);
      } catch (IOException e) {
        throw new IllegalArgumentException("truncated data", e);
      }
    }

    long longValue() {
      try {
        return in.readLong();
      } catch (IOException e) {
        throw new IllegalArgumentException("truncated data", e);
      }
    }

    void expectEnd() {
      try {
        if (in.available() != 0) {
          throw new IllegalArgumentException("unexpected trailing bytes");
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
