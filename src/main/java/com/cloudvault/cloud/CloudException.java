package com.cloudvault.cloud;

import java.io.IOException;

/** Cloud failure with a user-friendly message (never contains tokens). */
public class CloudException extends IOException {
	public enum Kind { NETWORK, AUTH, QUOTA, SERVER, OTHER }
	public final Kind kind;
	public CloudException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
	public CloudException(Kind kind, String message) { this(kind, message, null); }
	/** Auth problems need the user; everything else is worth retrying later. */
	public boolean retryable() { return kind != Kind.AUTH && kind != Kind.QUOTA; }
}
