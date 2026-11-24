package com.cloudvault.share;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.backup.BackupVerifier;
import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.HashUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * Share a backup: a plain (unencrypted) copy of the world is uploaded to a separate "CloudVault Shares" cloud folder and a link is made.
 * The share code contains that link, the world name and a checksum; a friend pastes it into CloudVault's import screen. Anyone who has
 * the code can download the world until the share expires or is revoked, so it is only ever made on request.
 */
public class ShareManager {
	public static final String PREFIX = "cvshare1:";
	public static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
	private static final Gson GSON = new Gson();

	public static class Share {
		public String id, worldName, remoteId, provider, name, link;
		public long created, expires, size;
	}

	private final BackupStore store;
	private final CloudManager cloud;
	private final Path file;
	private final List<Share> shares = new ArrayList<>();

	public ShareManager(BackupStore store, CloudManager cloud, Path root) {
		this.store = store; this.cloud = cloud; this.file = root.resolve("shares.json");
		try { if (Files.exists(file)) { List<Share> l = GSON.fromJson(Files.readString(file), new TypeToken<List<Share>>() {}.getType()); if (l != null) shares.addAll(l); } } catch (Exception ignored) { /* start empty */ }
	}

	private synchronized void save() { try { FileUtil.writeAtomic(file, GSON.toJson(shares)); } catch (IOException ignored) { /* best effort */ } }
	public synchronized List<Share> list() { return new ArrayList<>(shares); }

	/** Uploads a shareable copy and returns the share code. Blocking: network and disk. */
	public String create(BackupMetadata m, int days) throws IOException {
		CloudProvider p = cloud.provider();
		if (p == null || !p.isAuthenticated()) throw new CloudException(CloudException.Kind.AUTH, "Connect to the cloud first");
		Path zip = cloud.obtainVerifiedZip(m); // the complete world, decrypted
		if (Files.size(zip) > MAX_BYTES) throw new IOException("This backup is too big to share (limit 8 GB)");
		HashUtil.Hashes h = HashUtil.hashFile(zip);
		String id = UUID.randomUUID().toString().substring(0, 8);
		String name = "share-" + id + "-" + FileUtil.safeName(m.worldName) + ".zip";
		long expires = System.currentTimeMillis() + Math.max(1, days) * 86_400_000L;
		CloudProvider.RemoteFile rf = p.putFile("shares", "Shared backups", name, zip, Map.of("share", "1", "sha256", h.sha256()));
		String link = p.shareLink(rf.id(), Math.max(1, days) * 86_400);
		Share s = new Share(); s.id = id; s.worldName = m.worldName; s.remoteId = rf.id(); s.provider = p.id(); s.name = name; s.link = link; s.created = System.currentTimeMillis(); s.expires = expires; s.size = h.size();
		synchronized (this) { shares.add(s); }
		save();
		JsonObject o = new JsonObject(); o.addProperty("v", 1); o.addProperty("u", link); o.addProperty("n", m.worldName); o.addProperty("s", h.size()); o.addProperty("h", h.sha256());
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Stops sharing: deletes the shared copy (the link stops working). */
	public void revoke(Share s) throws IOException {
		CloudProvider p = cloud.provider();
		if (p != null && p.id().equals(s.provider) && s.remoteId != null) { try { p.deleteBackup(s.remoteId); } catch (IOException e) { if (!String.valueOf(e.getMessage()).contains("notfound")) throw e; } }
		synchronized (this) { shares.removeIf(x -> x.id.equals(s.id)); }
		save();
	}

	/** Deletes shares past their expiry (best effort; runs at start). */
	public void cleanExpired() {
		for (Share s : list()) if (s.expires > 0 && s.expires < System.currentTimeMillis()) try { revoke(s); } catch (Exception ignored) { /* tried again next start */ }
	}

	public record Parsed(String url, String worldName, long size, String sha256) {}

	/** Reads a share code or a plain https link. */
	public static Parsed parse(String text) throws IOException {
		String t = text == null ? "" : text.trim();
		if (t.startsWith(PREFIX)) {
			try {
				JsonObject o = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(t.substring(PREFIX.length())), StandardCharsets.UTF_8)).getAsJsonObject();
				String url = o.get("u").getAsString();
				checkUrl(url);
				String sha = o.has("h") ? o.get("h").getAsString() : null;
				return new Parsed(url, o.has("n") ? o.get("n").getAsString().replaceAll("\\p{Cntrl}", "") : "Shared world", o.has("s") ? o.get("s").getAsLong() : -1, sha != null && sha.matches("[0-9a-f]{64}") ? sha : null);
			} catch (IOException e) { throw e; } catch (RuntimeException e) { throw new IOException("That is not a valid share code"); }
		}
		checkUrl(t);
		return new Parsed(t, "Shared world", -1, null);
	}

	static void checkUrl(String url) throws IOException {
		String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
		boolean local = u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1");
		if (!u.startsWith("https://") && !local) throw new IOException("Share links must start with https://");
	}

	/** Downloads and checks the shared world. Returns a verified zip in {@code tempDir} (the caller deletes it). Blocking. */
	public static Path download(Parsed p, Path tempDir) throws IOException {
		Files.createDirectories(tempDir);
		Path part = tempDir.resolve("share-" + UUID.randomUUID() + ".part");
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(p.url())).timeout(Duration.ofHours(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			if (r.statusCode() / 100 != 2) throw new IOException(r.statusCode() == 404 || r.statusCode() == 403 ? "The share is no longer available (it may have expired)" : "The server answered " + r.statusCode());
			long total = 0;
			try (InputStream in = r.body(); var out = Files.newOutputStream(part)) {
				byte[] buf = new byte[256 * 1024]; int n;
				while ((n = in.read(buf)) > 0) { total += n; if (total > MAX_BYTES) throw new IOException("The download is larger than the 8 GB limit"); out.write(buf, 0, n); }
			}
			if (p.sha256() != null) { String got = HashUtil.hashFile(part).sha256(); if (!got.equals(p.sha256())) throw new IOException("The download does not match the share code (damaged or replaced)"); }
			BackupVerifier.verifyZip(part);
			Path ready = tempDir.resolve(part.getFileName().toString().replace(".part", ".zip"));
			Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING);
			return ready;
		} catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
		finally { try { Files.deleteIfExists(part); } catch (IOException ignored) { /* temp */ } }
	}
}
