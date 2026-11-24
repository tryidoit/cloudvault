package com.cloudvault.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 (our integrity hash) and MD5 (only to compare with Google Drive's md5Checksum). */
public final class HashUtil {
	private HashUtil() {}

	public record Hashes(String sha256, String md5, long size) {}

	public static Hashes hashFile(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			byte[] buf = new byte[256 * 1024];
			long total = 0;
			int n;
			while ((n = in.read(buf)) > 0) {
				sha.update(buf, 0, n);
				md5.update(buf, 0, n);
				total += n;
			}
			HexFormat hex = HexFormat.of();
			return new Hashes(hex.formatHex(sha.digest()), hex.formatHex(md5.digest()), total);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String sha256(String text) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
