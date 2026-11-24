package com.cloudvault.util;

import com.cloudvault.cloud.CloudException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.zip.ZipException;

/** Turns exceptions into short messages a player can act on, with anything secret removed. */
public final class ErrorText {
	private ErrorText() {}

	public static String friendly(Throwable e) {
		Throwable c = e;
		for (int i = 0; i < 5 && c.getCause() != null && !(c instanceof CloudException); i++) c = c.getCause();
		String msg = c.getMessage() == null ? "" : c.getMessage();
		String low = msg.toLowerCase(java.util.Locale.ROOT);
		String out;
		if (c instanceof CloudException) out = msg;
		else if (low.contains("no space left") || low.contains("not enough space") || low.contains("disk full") || low.contains("quota exceeded")) out = "The disk is full - free some space and try again";
		else if (c instanceof UnknownHostException || c instanceof ConnectException || c instanceof SocketTimeoutException || low.contains("connection reset") || low.contains("timed out"))
			out = "No internet connection - will try again later";
		else if (c instanceof AccessDeniedException || low.contains("permission denied")) out = "Permission denied - check that the folder can be written to";
		else if (c instanceof NoSuchFileException) out = "A world file or folder was not found";
		else if (low.contains("being used by another process") || (c instanceof FileSystemException && low.contains("used by another"))) out = "A file is in use by another program";
		else if (c instanceof ZipException || low.contains("zip") && low.contains("corrupt") || low.contains("checksum") || low.contains("damaged")) out = "The backup archive is damaged";
		else if (msg.isBlank()) out = "Something went wrong (" + c.getClass().getSimpleName() + ")";
		else out = msg;
		out = redact(out);
		return out.length() > 120 ? out.substring(0, 117) + "..." : out;
	}

	/** Removes anything that looks like a credential. Applied to every message that may reach the screen or a log. */
	public static String redact(String s) {
		if (s == null) return "";
		return s.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[hidden]")
			.replaceAll("(?i)((?:access_token|refresh_token|id_token|client_secret|code|password|secret|key)=)[^&\\s\"']+", "$1[hidden]")
			.replaceAll("GOCSPX-[A-Za-z0-9_-]+", "[hidden]")
			.replaceAll("ya29\\.[A-Za-z0-9._-]+", "[hidden]");
	}
}
