package dev.sobatista.tessera.core.crypto;

/** A cryptographic check failed: bad signature, wrong key, tampered data. Never swallowed silently. */
public class CryptoException extends RuntimeException {

  public CryptoException(String message) {
    super(message);
  }

  public CryptoException(String message, Throwable cause) {
    super(message, cause);
  }
}
