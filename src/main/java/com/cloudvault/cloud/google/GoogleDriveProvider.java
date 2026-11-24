package com.cloudvault.cloud.google;

import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudException.Kind;
import com.cloudvault.cloud.CloudProvider;
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Google Drive v3 over plain HTTPS. Layout: CloudVault / <world folder> / backup-<uuid>.zip. */
public class GoogleDriveProvider implements CloudProvider {
	private static final String API = "https://www.googleapis.com/drive/v3";
	private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
	private static final String FIELDS = "id,name,size,md5Checksum,appProperties,parents";
	private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

	private final GoogleAuth auth;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final Map<String, String> folderCache = new ConcurrentHashMap<>();

	public GoogleDriveProvider(GoogleAuth auth) { this.auth = auth; }
	public GoogleAuth auth() { return auth; }

	@Override public String id() { return "google_drive"; }
	@Override public boolean isAuthenticated() { return auth.isSignedIn(); }
	@Override public void authenticate() throws IOException { auth.signIn(); }
	@Override public void signOut() { auth.signOut(); folderCache.clear(); }

	@Override public Status testConnection() {
		if (!auth.isSignedIn()) return new Status(false, null, "Not signed in");
		try {
			JsonObject j = json(send(get(API + "/about?fields=user(emailAddress)")));
			return new Status(true, j.getAsJsonObject("user").get("emailAddress").getAsString(), "Connected");
		} catch (CloudException e) { return new Status(false, null, e.getMessage()); }
		catch (Exception e) { return new Status(false, null, "Could not check connection"); }
	}

	private static final String SYNC_ROOT = "CloudVault Sync";

	@Override public RemoteFile uploadBackup(Path file, String remoteName, String worldFolder, Map<String, String> props) throws IOException {
		return uploadTo("CloudVault", file, remoteName, worldFolder, props);
	}

	private static String areaRoot(String area) {
		return switch (area) { case "sync" -> SYNC_ROOT; case "account" -> "CloudVault Account"; case "shares" -> "CloudVault Shares"; default -> "CloudVault " + area; };
	}

	@Override public RemoteFile putFile(String area, String folder, String name, Path file, Map<String, String> props) throws IOException {
		return uploadTo(areaRoot(area), file, name, folder, props);
	}

	/** One sub-folder per world (or setting group) inside the area's top-level folder; cv_folder carries that folder's name. */
	@Override public List<RemoteFile> listFiles(String area, String onlyFolder, String namePrefix) throws IOException {
		String root = ensureFolder(areaRoot(area), "root");
		List<RemoteFile> out = new ArrayList<>();
		List<String[]> folders = new ArrayList<>();
		if (onlyFolder != null) folders.add(new String[]{ensureFolder(onlyFolder, root), onlyFolder});
		else {
			String fq = "trashed=false and mimeType='" + FOLDER_MIME + "' and '" + root + "' in parents";
			String page = null;
			do {
				JsonObject fj = json(send(get(API + "/files?pageSize=1000&fields=nextPageToken,files(id,name)&q=" + GoogleAuth.enc(fq) + (page != null ? "&pageToken=" + GoogleAuth.enc(page) : ""))));
				for (JsonElement fe : fj.getAsJsonArray("files")) folders.add(new String[]{fe.getAsJsonObject().get("id").getAsString(), fe.getAsJsonObject().get("name").getAsString()});
				page = fj.has("nextPageToken") ? fj.get("nextPageToken").getAsString() : null;
			} while (page != null);
		}
		for (String[] f : folders) {
			String q = "trashed=false and mimeType!='" + FOLDER_MIME + "' and name contains '" + esc(namePrefix) + "' and '" + f[0] + "' in parents";
			String p2 = null;
			do {
				JsonObject j = json(send(get(API + "/files?pageSize=1000&fields=nextPageToken,files(" + FIELDS + ")&q=" + GoogleAuth.enc(q) + (p2 != null ? "&pageToken=" + GoogleAuth.enc(p2) : ""))));
				for (JsonElement e : j.getAsJsonArray("files")) {
					RemoteFile rf = toRemote(e.getAsJsonObject());
					if (!rf.name().startsWith(namePrefix)) continue; // "contains" is looser than "starts with"
					Map<String, String> props = new HashMap<>(rf.props()); props.remove("cv_parent"); props.put("cv_folder", f[1]);
					out.add(new RemoteFile(rf.id(), rf.name(), rf.size(), rf.md5(), props));
				}
				p2 = j.has("nextPageToken") ? j.get("nextPageToken").getAsString() : null;
			} while (p2 != null);
		}
		return out;
	}

	/** Makes the file readable by anyone with the link. It stays so until the file is deleted (revoke = delete). */
	@Override public String shareLink(String remoteId, int seconds) throws IOException {
		JsonObject body = new JsonObject(); body.addProperty("role", "reader"); body.addProperty("type", "anyone");
		HttpRequest r = authed(HttpRequest.newBuilder(URI.create(API + "/files/" + GoogleAuth.enc(remoteId) + "/permissions?fields=id")))
			.header("Content-Type", "application/json").timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		exec(r, HttpResponse.BodyHandlers.ofString());
		return "https://drive.usercontent.google.com/download?id=" + GoogleAuth.enc(remoteId) + "&export=download&confirm=t";
	}

	private RemoteFile uploadTo(String rootName, Path file, String remoteName, String worldFolder, Map<String, String> props) throws IOException {
		String root = ensureFolder(rootName, "root");
		String folder = ensureFolder(worldFolder, root);
		JsonObject meta = new JsonObject();
		meta.addProperty("name", remoteName);
		JsonArray parents = new JsonArray(); parents.add(folder); meta.add("parents", parents);
		JsonObject ap = new JsonObject(); props.forEach(ap::addProperty); meta.add("appProperties", ap);

		HttpRequest init = authed(HttpRequest.newBuilder(URI.create(UPLOAD + "?uploadType=resumable&fields=" + FIELDS)))
			.header("Content-Type", "application/json; charset=UTF-8")
			.header("X-Upload-Content-Type", "application/zip")
			.header("X-Upload-Content-Length", Long.toString(Files.size(file)))
			.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(meta.toString())).build();
		HttpResponse<String> ir = exec(init, HttpResponse.BodyHandlers.ofString());
		String loc = ir.headers().firstValue("Location").orElseThrow(() -> new CloudException(Kind.SERVER, "Google Drive did not start the upload"));

		HttpRequest put = HttpRequest.newBuilder(URI.create(loc)).header("Content-Type", "application/zip")
			.timeout(Duration.ofHours(3)).PUT(com.cloudvault.cloud.Throttle.file(file)).build();
		HttpResponse<String> ur = exec(put, HttpResponse.BodyHandlers.ofString());
		return toRemote(json(ur.body()));
	}

	@Override public void downloadBackup(String remoteId, Path target) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		HttpRequest r = authed(HttpRequest.newBuilder(URI.create(API + "/files/" + GoogleAuth.enc(remoteId) + "?alt=media")))
			.timeout(Duration.ofHours(3)).GET().build();
		exec(r, HttpResponse.BodyHandlers.ofFile(target));
	}

	@Override public List<RemoteFile> listBackups(String worldFolder) throws IOException {
		String q = "trashed=false and mimeType!='" + FOLDER_MIME + "' and name contains 'backup-'";
		if (worldFolder != null) q += " and '" + ensureFolder(worldFolder, ensureFolder("CloudVault", "root")) + "' in parents";
		List<RemoteFile> out = new ArrayList<>();
		String page = null;
		do {
			String url = API + "/files?pageSize=1000&fields=nextPageToken,files(" + FIELDS + ")&q=" + GoogleAuth.enc(q) + (page != null ? "&pageToken=" + GoogleAuth.enc(page) : "");
			JsonObject j = json(send(get(url)));
			j.getAsJsonArray("files").forEach(e -> out.add(toRemote(e.getAsJsonObject())));
			page = j.has("nextPageToken") ? j.get("nextPageToken").getAsString() : null;
		} while (page != null);
		return out;
	}

	@Override public void deleteBackup(String remoteId) throws IOException {
		HttpRequest r = authed(HttpRequest.newBuilder(URI.create(API + "/files/" + GoogleAuth.enc(remoteId)))).timeout(Duration.ofSeconds(60)).DELETE().build();
		try { exec(r, HttpResponse.BodyHandlers.ofString()); }
		catch (CloudException e) { if (!"notfound".equals(e.getMessage())) throw e; } // already gone = fine
	}

	/** Lists every backup and tells which world folder (cv_folder) each one is in. */
	@Override public List<RemoteFile> listAllBackups() throws IOException {
		List<RemoteFile> raw = listBackups(null);
		Map<String, String> names = new HashMap<>();
		List<RemoteFile> out = new ArrayList<>();
		for (RemoteFile f : raw) {
			Map<String, String> props = new HashMap<>(f.props());
			String parent = props.remove("cv_parent");
			if (parent != null) {
				String name = names.get(parent);
				if (name == null) {
					try { name = json(send(get(API + "/files/" + GoogleAuth.enc(parent) + "?fields=name"))).get("name").getAsString(); }
					catch (IOException e) { name = ""; }
					names.put(parent, name);
				}
				if (!name.isEmpty()) props.put("cv_folder", name);
			}
			out.add(new RemoteFile(f.id(), f.name(), f.size(), f.md5(), props));
		}
		return out;
	}

	@Override public StorageInfo storageInfo() throws IOException {
		JsonObject q = json(send(get(API + "/about?fields=storageQuota"))).getAsJsonObject("storageQuota");
		long used = q.has("usage") ? Long.parseLong(q.get("usage").getAsString()) : 0;
		long total = q.has("limit") ? Long.parseLong(q.get("limit").getAsString()) : -1;
		return new StorageInfo(used, total);
	}

	@Override public String folderUrl() throws IOException {
		return "https://drive.google.com/drive/folders/" + ensureFolder("CloudVault", "root");
	}

	// ---- helpers ----

	private String ensureFolder(String name, String parent) throws IOException {
		String key = parent + "/" + name;
		String cached = folderCache.get(key);
		if (cached != null) return cached;
		String q = "mimeType='" + FOLDER_MIME + "' and trashed=false and name='" + esc(name) + "' and '" + parent + "' in parents";
		JsonArray files = json(send(get(API + "/files?fields=files(id)&q=" + GoogleAuth.enc(q)))).getAsJsonArray("files");
		String id;
		if (files.size() > 0) id = files.get(0).getAsJsonObject().get("id").getAsString();
		else {
			JsonObject body = new JsonObject(); body.addProperty("name", name); body.addProperty("mimeType", FOLDER_MIME);
			JsonArray p = new JsonArray(); p.add(parent); body.add("parents", p);
			HttpRequest r = authed(HttpRequest.newBuilder(URI.create(API + "/files?fields=id"))).header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
			id = json(exec(r, HttpResponse.BodyHandlers.ofString()).body()).get("id").getAsString();
		}
		folderCache.put(key, id);
		return id;
	}

	private static String esc(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

	private HttpRequest get(String url) throws IOException {
		return authed(HttpRequest.newBuilder(URI.create(url))).timeout(Duration.ofSeconds(60)).GET().build();
	}

	private HttpRequest.Builder authed(HttpRequest.Builder b) throws IOException { return b.header("Authorization", "Bearer " + auth.accessToken()); }

	private String send(HttpRequest r) throws IOException { return exec(r, HttpResponse.BodyHandlers.ofString()).body(); }

	/** Executes, maps HTTP/network errors to CloudException. Retries once on 401 with a fresh token. */
	private <T> HttpResponse<T> exec(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			HttpResponse<T> r = http.send(req, handler);
			if (r.statusCode() == 401) {
				auth.invalidateAccessToken();
				HttpRequest retry = HttpRequest.newBuilder(req.uri()).method(req.method(), req.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
					.header("Authorization", "Bearer " + auth.accessToken()).build();
				r = http.send(retry, handler);
			}
			int sc = r.statusCode();
			if (sc >= 200 && sc < 300) return r;
			if (sc == 404) throw new CloudException(Kind.OTHER, "notfound");
			if (sc == 401) throw new CloudException(Kind.AUTH, "Google Drive rejected the sign-in - please reconnect");
			if (sc == 403 || sc == 429) throw refused(sc, r.body());
			throw new CloudException(Kind.SERVER, "Google Drive error (" + sc + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new CloudException(Kind.NETWORK, "Interrupted");
		} catch (CloudException e) {
			throw e;
		} catch (IOException e) { // timeouts, resets, DNS failure: treat as network trouble (retried later)
			throw new CloudException(Kind.NETWORK, "No connection to Google Drive", e);
		}
	}

	/** Turns a 403/429 into a specific message using Google's own error reason (also logged in full). */
	private static CloudException refused(int sc, Object body) {
		String reason = "", msg = "";
		if (body instanceof String str) {
			com.cloudvault.CloudVault.LOGGER.warn("Google Drive {} reply: {}", sc, str.length() > 1500 ? str.substring(0, 1500) : str);
			try {
				JsonObject e = JsonParser.parseString(str).getAsJsonObject().getAsJsonObject("error");
				if (e.has("message")) msg = e.get("message").getAsString();
				if (e.has("errors") && e.getAsJsonArray("errors").size() > 0) reason = e.getAsJsonArray("errors").get(0).getAsJsonObject().get("reason").getAsString();
				if (reason.isEmpty() && e.has("status")) reason = e.get("status").getAsString();
			} catch (RuntimeException ignored) { /* not JSON */ }
		}
		String r = reason.toLowerCase();
		if (r.contains("accessnotconfigured") || r.contains("service_disabled") || msg.contains("has not been used") || msg.contains("is disabled"))
			return new CloudException(Kind.OTHER, "The Google Drive API is not enabled for this app's Google Cloud project");
		if (r.contains("storagequota")) return new CloudException(Kind.QUOTA, "Your Google Drive storage is full");
		if (r.contains("ratelimit") || r.contains("resource_exhausted") || sc == 429) return new CloudException(Kind.SERVER, "Google Drive rate limit reached - will retry later");
		if (r.contains("insufficient") || r.contains("scope")) return new CloudException(Kind.AUTH, "Google Drive permission missing - disconnect and reconnect");
		return new CloudException(Kind.OTHER, "Google Drive refused the request" + (reason.isEmpty() ? "" : " (" + reason + ")"));
	}

	private static JsonObject json(String s) throws CloudException {
		try { return JsonParser.parseString(s).getAsJsonObject(); }
		catch (JsonParseException | IllegalStateException e) { throw new CloudException(Kind.SERVER, "Unexpected reply from Google Drive"); }
	}

	private static RemoteFile toRemote(JsonObject j) {
		Map<String, String> props = new HashMap<>();
		if (j.has("appProperties")) j.getAsJsonObject("appProperties").entrySet().forEach(e -> props.put(e.getKey(), e.getValue().getAsString()));
		if (j.has("parents") && j.getAsJsonArray("parents").size() > 0) props.put("cv_parent", j.getAsJsonArray("parents").get(0).getAsString());
		return new RemoteFile(j.get("id").getAsString(), j.has("name") ? j.get("name").getAsString() : "",
			j.has("size") ? Long.parseLong(j.get("size").getAsString()) : -1, j.has("md5Checksum") ? j.get("md5Checksum").getAsString() : null, props);
	}
}
