package com.cloudvault.cloud.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** AWS Signature Version 4 (header-based) for S3-compatible services. Pure functions, unit-tested against AWS's published examples. */
public final class SigV4 {
	private SigV4() {}

	public static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

	public static byte[] hmac(byte[] key, String data) {
		try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data.getBytes(StandardCharsets.UTF_8)); }
		catch (Exception e) { throw new IllegalStateException(e); }
	}

	public static String sha256Hex(byte[] data) {
		try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (Exception e) { throw new IllegalStateException(e); }
	}

	/** RFC 3986 encoding as S3 wants it. Slashes stay when {@code keepSlash}. */
	public static String encode(String s, boolean keepSlash) {
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xFF);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') sb.append(c);
			else if (c == '/' && keepSlash) sb.append('/');
			else sb.append('%').append(String.format("%02X", b & 0xFF));
		}
		return sb.toString();
	}

	/** Query string (including the signature) of a pre-signed URL; only the host header is signed, the payload is UNSIGNED-PAYLOAD. */
	public static String presignQuery(String method, String canonicalUri, String host, String accessKey, String secretKey, String amzDate, String region, String service, int expires) {
		String date = amzDate.substring(0, 8);
		String scope = date + "/" + region + "/" + service + "/aws4_request";
		TreeMap<String, String> q = new TreeMap<>();
		q.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256"); q.put("X-Amz-Credential", accessKey + "/" + scope); q.put("X-Amz-Date", amzDate);
		q.put("X-Amz-Expires", Integer.toString(expires)); q.put("X-Amz-SignedHeaders", "host");
		StringBuilder cq = new StringBuilder();
		q.forEach((k, v) -> { if (cq.length() > 0) cq.append('&'); cq.append(encode(k, false)).append('=').append(encode(v, false)); });
		String canonical = method + "\n" + canonicalUri + "\n" + cq + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
		String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
		byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
		k = hmac(k, region); k = hmac(k, service); k = hmac(k, "aws4_request");
		return cq + "&X-Amz-Signature=" + hex(hmac(k, toSign));
	}

	/**
	 * @param headers all headers to sign (names are lower-cased here); must include "host" and "x-amz-date"; "x-amz-content-sha256" for S3
	 * @param query   unencoded query parameters
	 * @return value for the Authorization header
	 */
	public static String authorization(String method, String canonicalUri, Map<String, String> query, Map<String, String> headers,
	                                   String payloadHash, String accessKey, String secretKey, String amzDate, String region, String service) {
		String date = amzDate.substring(0, 8);
		TreeMap<String, String> q = new TreeMap<>();
		query.forEach((k, v) -> q.put(encode(k, false), encode(v, false)));
		StringBuilder cq = new StringBuilder();
		q.forEach((k, v) -> { if (cq.length() > 0) cq.append('&'); cq.append(k).append('=').append(v); });

		TreeMap<String, String> h = new TreeMap<>();
		headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.trim().replaceAll("\\s+", " ")));
		StringBuilder ch = new StringBuilder(), sh = new StringBuilder();
		h.forEach((k, v) -> { ch.append(k).append(':').append(v).append('\n'); if (sh.length() > 0) sh.append(';'); sh.append(k); });

		String canonical = method + "\n" + canonicalUri + "\n" + cq + "\n" + ch + "\n" + sh + "\n" + payloadHash;
		String scope = date + "/" + region + "/" + service + "/aws4_request";
		String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
		byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
		k = hmac(k, region); k = hmac(k, service); k = hmac(k, "aws4_request");
		return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + sh + ", Signature=" + hex(hmac(k, toSign));
	}
}
