package com.cloudvault.cloud.google;

import java.io.InputStream;
import java.util.Properties;

/** Optional Google OAuth client shipped inside the mod jar (src/main/resources/cloudvault-oauth.properties). */
public final class BuiltInOAuth {
	private BuiltInOAuth() {}
	private static final Properties P = new Properties();
	static {
		try (InputStream in = BuiltInOAuth.class.getResourceAsStream("/cloudvault-oauth.properties")) {
			if (in != null) P.load(in);
		} catch (Exception ignored) { /* no built-in client */ }
	}
	public static String clientId() { return P.getProperty("clientId", "").trim(); }
	public static String clientSecret() { return P.getProperty("clientSecret", "").trim(); }
	public static boolean available() { return !clientId().isEmpty(); }
}
