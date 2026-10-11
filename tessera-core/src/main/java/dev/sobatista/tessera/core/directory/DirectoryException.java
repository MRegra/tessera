package dev.sobatista.tessera.core.directory;

/** The directory refused a request: unknown user, bad credentials, not a member, not the owner... */
public class DirectoryException extends RuntimeException {

  public enum Reason { BAD_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT }

  private final Reason reason;

  public DirectoryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
