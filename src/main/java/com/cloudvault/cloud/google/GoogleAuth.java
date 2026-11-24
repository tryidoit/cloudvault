package com.cloudvault.cloud.google;

import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudException.Kind;
import com.cloudvault.util.FileUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Google OAuth 2.0 for installed apps: system browser + PKCE + loopback redirect (127.0.0.1).
 * Only scope: drive.file (CloudVault can only see files it created). Tokens are NEVER logged.
 */
public class GoogleAuth {
	static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
	private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
	private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final Path tokenFile;
	private final Supplier<String> clientId, clientSecret;
	private volatile Consumer<String> urlOpener = u -> {};
	private String accessToken; private long expiresAtMs; private String refreshToken;

	public GoogleAuth(Path authDir, Supplier<String> clientId, Supplier<String> clientSecret) { this(authDir, "google_tokens.json", clientId, clientSecret); }

	/** @param tokenFileName a different file lets a second Google account (the second destination) sign in separately */
	public GoogleAuth(Path authDir, String tokenFileName, Supplier<String> clientId, Supplier<String> clientSecret) {
		this.tokenFile = authDir.resolve(tokenFileName);
		this.clientId = clientId; this.clientSecret = clientSecret;
		load();
	}

	/** The client module sets this to open the system browser (e.g. Minecraft's Util.getPlatform().openUri). */
	public void setUrlOpener(Consumer<String> opener) { this.urlOpener = opener; }
	public synchronized boolean isSignedIn() { return refreshToken != null; }

	public synchronized void signOut() {
		accessToken = null; refreshToken = null; expiresAtMs = 0;
		try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) {}
	}

	/** Blocks (up to 5 min) while the user signs in through the browser. Call from a background thread. */
	public void signIn() throws IOException {
		if (clientId.get() == null || clientId.get().isBlank())
			throw new CloudException(Kind.AUTH, "Set your Google OAuth client ID in CloudVault settings first");
		String verifier = random(48), state = random(16);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
		CompletableFuture<String> codeFuture = new CompletableFuture<>();
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		int port = server.getAddress().getPort();
		String redirect = "http://127.0.0.1:" + port + "/callback";
		server.createContext("/callback", ex -> {
			Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
			String st = q.get("state");
			boolean mine = st != null && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), st.getBytes(StandardCharsets.UTF_8));
			String msg; int status = 200;
			if (!mine) { msg = "This is not the expected sign-in response."; status = 400; } // a stray request must not end or hijack the real sign-in
			else if (q.get("code") != null) { codeFuture.complete(q.get("code")); msg = "CloudVault is connected. You can close this tab and return to Minecraft."; }
			else { codeFuture.completeExceptionally(new CloudException(Kind.AUTH, "Google sign-in was cancelled or failed")); msg = "Sign-in failed. You can close this tab."; }
			byte[] body = ("<html><body style='font-family:sans-serif'><h3>" + msg + "</h3></body></html>").getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.getResponseHeaders().add("Cache-Control", "no-store");
			ex.sendResponseHeaders(status, body.length);
			try (var os = ex.getResponseBody()) { os.write(body); }
		});
		server.start();
		try {
			String url = AUTH_URL + "?response_type=code&access_type=offline&prompt=consent"
				+ "&client_id=" + enc(clientId.get()) + "&redirect_uri=" + enc(redirect) + "&scope=" + enc(SCOPE)
				+ "&state=" + state + "&code_challenge=" + challenge + "&code_challenge_method=S256";
			urlOpener.accept(url);
			String code = codeFuture.get(5, TimeUnit.MINUTES);
			Map<String, String> form = new HashMap<>();
			form.put("code", code); form.put("client_id", clientId.get()); form.put("redirect_uri", redirect);
			form.put("grant_type", "authorization_code"); form.put("code_verifier", verifier);
			if (!clientSecret.get().isBlank()) form.put("client_secret", clientSecret.get());
			applyTokens(tokenRequest(form), true);
		} catch (java.util.concurrent.ExecutionException e) {
			throw e.getCause() instanceof IOException io ? io : new CloudException(Kind.AUTH, "Google sign-in failed");
		} catch (java.util.concurrent.TimeoutException e) {
			throw new CloudException(Kind.AUTH, "Google sign-in timed out");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new CloudException(Kind.AUTH, "Sign-in interrupted");
		} finally {
			server.stop(1); // let the browser finish receiving its "signed in" or "failed" page
		}
	}

	/** A valid access token, refreshing when needed. Throws AUTH if the user must sign in again. */
	public synchronized String accessToken() throws IOException {
		if (refreshToken == null) throw new CloudException(Kind.AUTH, "Not signed in to Google Drive");
		if (accessToken == null || System.currentTimeMillis() > expiresAtMs - 60_000) refresh();
		return accessToken;
	}

	public synchronized void invalidateAccessToken() { accessToken = null; }

	private void refresh() throws IOException {
		Map<String, String> form = new HashMap<>();
		form.put("client_id", clientId.get()); form.put("refresh_token", refreshToken); form.put("grant_type", "refresh_token");
		if (!clientSecret.get().isBlank()) form.put("client_secret", clientSecret.get());
		try {
			applyTokens(tokenRequest(form), false);
		} catch (CloudException e) {
			if (e.kind == Kind.AUTH) signOut(); // refresh token revoked/expired: need a fresh sign-in
			throw e;
		}
	}

	private JsonObject tokenRequest(Map<String, String> form) throws IOException {
		StringBuilder sb = new StringBuilder();
		form.forEach((k, v) -> sb.append(sb.length() > 0 ? "&" : "").append(enc(k)).append('=').append(enc(v)));
		HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
		try {
			HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 400 || r.statusCode() == 401) throw new CloudException(Kind.AUTH, "Google sign-in expired - please sign in again");
			if (r.statusCode() >= 300) throw new CloudException(Kind.SERVER, "Google sign-in service error (" + r.statusCode() + ")");
			return JsonParser.parseString(r.body()).getAsJsonObject();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new CloudException(Kind.NETWORK, "Interrupted");
		} catch (CloudException e) {
			throw e;
		} catch (IOException e) {
			throw new CloudException(Kind.NETWORK, "No connection to Google", e);
		} catch (com.google.gson.JsonParseException | IllegalStateException e) {
			throw new CloudException(Kind.SERVER, "Unexpected reply from Google");
		}
	}

	private synchronized void applyTokens(JsonObject j, boolean mustHaveRefresh) throws IOException {
		accessToken = j.get("access_token").getAsString();
		expiresAtMs = System.currentTimeMillis() + j.get("expires_in").getAsLong() * 1000;
		if (j.has("refresh_token")) refreshToken = j.get("refresh_token").getAsString();
		else if (mustHaveRefresh) throw new CloudException(Kind.AUTH, "Google did not return a refresh token");
		JsonObject out = new JsonObject(); out.addProperty("refresh_token", refreshToken);
		FileUtil.writeSecret(tokenFile, out.toString());
		try { Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) {} // not on Windows
	}

	private void load() {
		try {
			if (Files.exists(tokenFile)) refreshToken = JsonParser.parseString(Files.readString(tokenFile)).getAsJsonObject().get("refresh_token").getAsString();
		} catch (Exception ignored) { refreshToken = null; }
	}

	private static String random(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
	private static byte[] sha256(String s) { try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)); } catch (Exception e) { throw new IllegalStateException(e); } }
	static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
	private static Map<String, String> parseQuery(String q) {
		Map<String, String> m = new HashMap<>();
		if (q == null) return m;
		for (String p : q.split("&")) { int i = p.indexOf('='); if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8)); }
		return m;
	}
}
