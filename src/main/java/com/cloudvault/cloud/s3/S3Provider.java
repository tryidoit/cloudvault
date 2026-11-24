package com.cloudvault.cloud.s3;

import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudException.Kind;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.util.HashUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * S3-compatible storage (AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO...) over plain HTTPS with Signature V4.
 * Layout: CloudVault/<world folder>/<backup file>. Large files use multipart upload; every request carries Content-MD5,
 * so the server itself rejects data that arrives damaged.
 */
public class S3Provider implements CloudProvider {
	public record Cfg(String endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {
		public boolean complete() { return !blank(endpoint) && !blank(bucket) && !blank(accessKey) && !blank(secretKey); }
		private static boolean blank(String s) { return s == null || s.isBlank(); }
	}

	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
	private static final String EMPTY_SHA = SigV4.sha256Hex(new byte[0]);
	private final Supplier<Cfg> cfg;
	private final long partSize;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	public S3Provider(Supplier<Cfg> cfg) { this(cfg, 16L << 20); }
	/** Part size is configurable for tests; S3 requires at least 5 MiB for real parts except the last. */
	public S3Provider(Supplier<Cfg> cfg, long partSize) { this.cfg = cfg; this.partSize = partSize; }

	@Override public String id() { return "s3"; }
	@Override public boolean isAuthenticated() { Cfg c = cfg.get(); return c != null && c.complete(); }
	@Override public void signOut() {}

	/** No browser sign-in: checks that the saved settings work. */
	@Override public void authenticate() throws IOException {
		Cfg c = need();
		call("HEAD", c, null, Map.of(), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString());
	}

	@Override public Status testConnection() {
		if (!isAuthenticated()) return new Status(false, null, "Storage settings are incomplete");
		try { authenticate(); Cfg c = cfg.get(); return new Status(true, c.bucket() + " @ " + URI.create(c.endpoint()).getHost(), "Connected"); }
		catch (CloudException e) { return new Status(false, null, e.getMessage()); }
		catch (Exception e) { return new Status(false, null, "Could not check connection"); }
	}

	@Override public RemoteFile uploadBackup(Path file, String remoteName, String worldFolder, Map<String, String> props) throws IOException {
		return uploadTo("CloudVault/", file, remoteName, worldFolder, props);
	}

	private static String areaRoot(String area) {
		return switch (area) { case "sync" -> "CloudVault Sync/"; case "account" -> "CloudVault Account/"; case "shares" -> "CloudVault Shares/"; default -> "CloudVault " + area + "/"; };
	}

	@Override public RemoteFile putFile(String area, String folder, String name, Path file, Map<String, String> props) throws IOException {
		return uploadTo(areaRoot(area), file, name, folder, props);
	}

	@Override public List<RemoteFile> listFiles(String area, String folder, String namePrefix) throws IOException {
		return listUnder(areaRoot(area) + (folder == null ? "" : folder + "/"), namePrefix);
	}

	/** A pre-signed GET link (AWS limit: at most 7 days). */
	@Override public String shareLink(String remoteId, int seconds) throws IOException {
		Cfg c = need();
		URI ep = URI.create(c.endpoint().trim());
		String hostOnly = c.pathStyle() ? ep.getHost() : c.bucket() + "." + ep.getHost();
		String host = hostOnly + (ep.getPort() > 0 ? ":" + ep.getPort() : "");
		String path = c.pathStyle() ? "/" + SigV4.encode(c.bucket(), false) + "/" + SigV4.encode(remoteId, true) : "/" + SigV4.encode(remoteId, true);
		String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ);
		String q = SigV4.presignQuery("GET", path, host, c.accessKey().trim(), c.secretKey().trim(), amzDate, blankTo(c.region(), "us-east-1"), "s3", Math.max(60, Math.min(604_800, seconds)));
		return ep.getScheme() + "://" + host + path + "?" + q;
	}

	private RemoteFile uploadTo(String rootPrefix, Path file, String remoteName, String worldFolder, Map<String, String> props) throws IOException {
		Cfg c = need();
		String key = rootPrefix + worldFolder + "/" + remoteName;
		long size = Files.size(file);
		Map<String, String> meta = new TreeMap<>();
		props.forEach((k, v) -> meta.put("x-amz-meta-" + k.toLowerCase(Locale.ROOT), v));
		if (size <= partSize) {
			HashUtil.Hashes h = HashUtil.hashFile(file);
			Map<String, String> hd = new TreeMap<>(meta);
			hd.put("content-md5", b64(hexToBytes(h.md5())));
			call("PUT", c, key, Map.of(), hd, com.cloudvault.cloud.Throttle.file(file), h.sha256(), HttpResponse.BodyHandlers.ofString());
			return verifyRemote(c, key, remoteName, size, h.md5(), props);
		}
		String uploadId = parseXml(call("POST", c, key, Map.of("uploads", ""), meta, HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString()).body())
			.getElementsByTagName("UploadId").item(0).getTextContent();
		try (InputStream in = Files.newInputStream(file)) {
			List<String> etags = new ArrayList<>();
			for (int n = 1; ; n++) {
				byte[] part = in.readNBytes((int) partSize);
				if (part.length == 0) break;
				Map<String, String> hd = Map.of("content-md5", b64(md5(part)));
				HttpResponse<String> r = call("PUT", c, key, Map.of("partNumber", Integer.toString(n), "uploadId", uploadId), hd,
					com.cloudvault.cloud.Throttle.bytes(part), SigV4.sha256Hex(part), HttpResponse.BodyHandlers.ofString());
				etags.add(r.headers().firstValue("ETag").orElseThrow(() -> new CloudException(Kind.SERVER, "Storage did not confirm an upload part")));
			}
			StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
			for (int i = 0; i < etags.size(); i++) xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>").append(etags.get(i).replace("\"", "&quot;")).append("</ETag></Part>");
			byte[] body = xml.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
			HttpResponse<String> done = call("POST", c, key, Map.of("uploadId", uploadId), Map.of(), HttpRequest.BodyPublishers.ofByteArray(body), SigV4.sha256Hex(body), HttpResponse.BodyHandlers.ofString());
			if (done.body().contains("<Error>")) throw new CloudException(Kind.SERVER, "Storage could not finish the upload");
		} catch (IOException | RuntimeException e) {
			try { call("DELETE", c, key, Map.of("uploadId", uploadId), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString()); } catch (Exception ignored) { /* best effort */ }
			throw e;
		}
		return verifyRemote(c, key, remoteName, size, null, props); // multipart: integrity was enforced per part by Content-MD5
	}

	private RemoteFile verifyRemote(Cfg c, String key, String name, long expectedSize, String md5, Map<String, String> props) throws IOException {
		HttpResponse<String> h = call("HEAD", c, key, Map.of(), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString());
		long len = h.headers().firstValueAsLong("Content-Length").orElse(-1);
		return new RemoteFile(key, name, len < 0 ? expectedSize : len, md5, props);
	}

	@Override public void downloadBackup(String remoteId, Path target) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		call("GET", need(), remoteId, Map.of(), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofFile(target));
	}

	@Override public List<RemoteFile> listBackups(String worldFolder) throws IOException {
		return listUnder("CloudVault/" + (worldFolder == null ? "" : worldFolder + "/"), "backup-");
	}

	private List<RemoteFile> listUnder(String prefix, String namePrefix) throws IOException {
		Cfg c = need();
		List<RemoteFile> out = new ArrayList<>();
		String token = null;
		do {
			Map<String, String> q = new TreeMap<>(Map.of("list-type", "2", "prefix", prefix));
			if (token != null) q.put("continuation-token", token);
			Document d = parseXml(call("GET", c, null, q, Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString()).body());
			NodeList items = d.getElementsByTagName("Contents");
			for (int i = 0; i < items.getLength(); i++) {
				Element e = (Element) items.item(i);
				String key = text(e, "Key");
				String name = key.substring(key.lastIndexOf('/') + 1);
				if (!name.startsWith(namePrefix)) continue;
				String etag = text(e, "ETag").replace("\"", "");
				String[] seg = key.split("/");
				Map<String, String> props = seg.length >= 3 ? Map.of("cv_folder", seg[1]) : Map.of();
				out.add(new RemoteFile(key, name, Long.parseLong(text(e, "Size")), etag.matches("[0-9a-fA-F]{32}") ? etag : null, props));
			}
			token = "true".equals(text(d.getDocumentElement(), "IsTruncated")) ? text(d.getDocumentElement(), "NextContinuationToken") : null;
		} while (token != null && !token.isEmpty());
		return out;
	}

	@Override public void deleteBackup(String remoteId) throws IOException {
		try { call("DELETE", need(), remoteId, Map.of(), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString()); }
		catch (CloudException e) { if (!"notfound".equals(e.getMessage())) throw e; }
	}

	/** The listing has no metadata, so read it with one HEAD request. */
	@Override public RemoteFile details(RemoteFile f) throws IOException {
		HttpResponse<String> h = call("HEAD", need(), f.id(), Map.of(), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_SHA, HttpResponse.BodyHandlers.ofString());
		Map<String, String> props = new HashMap<>(f.props());
		h.headers().map().forEach((k, v) -> { if (k.toLowerCase(Locale.ROOT).startsWith("x-amz-meta-") && !v.isEmpty()) props.put(k.substring(11).toLowerCase(Locale.ROOT), v.get(0)); });
		return new RemoteFile(f.id(), f.name(), f.size(), f.md5(), props);
	}

	@Override public StorageInfo storageInfo() throws IOException {
		long used = 0; for (RemoteFile f : listAllBackups()) used += f.size();
		return new StorageInfo(used, -1); // object storage has no per-account quota to report
	}

	// ---- plumbing ----

	private Cfg need() throws CloudException {
		Cfg c = cfg.get();
		if (c == null || !c.complete()) throw new CloudException(Kind.AUTH, "S3 storage settings are incomplete");
		// Backups and keys must never cross the network unencrypted; plain http is only allowed for a server on this computer (testing).
		String ep = c.endpoint().trim().toLowerCase(Locale.ROOT);
		boolean local = ep.startsWith("http://localhost") || ep.startsWith("http://127.0.0.1") || ep.startsWith("http://[::1]");
		if (!ep.startsWith("https://") && !local) throw new CloudException(Kind.AUTH, "The storage address must start with https://");
		return c;
	}

	private <T> HttpResponse<T> call(String method, Cfg c, String key, Map<String, String> query, Map<String, String> extra,
	                                 HttpRequest.BodyPublisher body, String payloadHash, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			URI ep = URI.create(c.endpoint().trim());
			String hostOnly = c.pathStyle() ? ep.getHost() : c.bucket() + "." + ep.getHost();
			String host = hostOnly + (ep.getPort() > 0 ? ":" + ep.getPort() : "");
			String path = (c.pathStyle() ? "/" + SigV4.encode(c.bucket(), false) + (key == null ? "" : "/" + SigV4.encode(key, true))
			                             : (key == null ? "/" : "/" + SigV4.encode(key, true)));
			TreeMap<String, String> q = new TreeMap<>(query);
			StringBuilder qs = new StringBuilder();
			q.forEach((k, v) -> { qs.append(qs.length() == 0 ? '?' : '&').append(SigV4.encode(k, false)).append('=').append(SigV4.encode(v, false)); });
			String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ);

			Map<String, String> signed = new TreeMap<>();
			signed.put("host", host); signed.put("x-amz-date", amzDate); signed.put("x-amz-content-sha256", payloadHash);
			signed.putAll(extra);
			String auth = SigV4.authorization(method, path, q, signed, payloadHash, c.accessKey().trim(), c.secretKey().trim(), amzDate,
				blankTo(c.region(), "us-east-1"), "s3");

			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ep.getScheme() + "://" + host + path + qs)).timeout(Duration.ofHours(3)).method(method, body);
			signed.forEach((k, v) -> { if (!k.equals("host")) b.header(k, v); });
			b.header("Authorization", auth);
			HttpResponse<T> r = http.send(b.build(), handler);
			int sc = r.statusCode();
			if (sc >= 200 && sc < 300) return r;
			throw mapError(sc, r.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); throw new CloudException(Kind.NETWORK, "Interrupted");
		} catch (CloudException e) { throw e;
		} catch (IOException e) { throw new CloudException(Kind.NETWORK, "No connection to the storage server", e);
		} catch (IllegalArgumentException e) { throw new CloudException(Kind.AUTH, "The storage endpoint address is not valid");
		}
	}

	private static CloudException mapError(int sc, Object body) {
		String code = "";
		try {
			String s = body instanceof String str ? str : body instanceof Path p ? Files.readString(p) : "";
			if (s.contains("<Code>")) code = s.substring(s.indexOf("<Code>") + 6, s.indexOf("</Code>"));
			if (body instanceof Path p) Files.deleteIfExists(p);
		} catch (Exception ignored) { /* no details */ }
		if (sc == 404 && (code.equals("NoSuchBucket"))) return new CloudException(Kind.AUTH, "Bucket not found - check the bucket name");
		if (sc == 404) return new CloudException(Kind.OTHER, "notfound");
		if (code.equals("SignatureDoesNotMatch") || code.equals("InvalidAccessKeyId")) return new CloudException(Kind.AUTH, "Storage rejected the access key or secret key");
		if (sc == 401 || sc == 403) return new CloudException(Kind.AUTH, "Access denied - check the keys and that they can read and write this bucket");
		if (code.equals("EntityTooLarge") || code.equals("QuotaExceeded")) return new CloudException(Kind.QUOTA, "Storage quota exceeded");
		if (code.equals("BadDigest") || code.equals("InvalidDigest")) return new CloudException(Kind.NETWORK, "Upload arrived damaged - will retry");
		if (sc == 429 || sc >= 500) return new CloudException(Kind.SERVER, "Storage server is busy or unavailable (" + sc + ") - will retry");
		return new CloudException(Kind.OTHER, "Storage error (" + sc + (code.isEmpty() ? "" : ", " + code) + ")");
	}

	private static Document parseXml(String xml) throws IOException {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setXIncludeAware(false); f.setExpandEntityReferences(false);
			return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) { throw new CloudException(Kind.SERVER, "Unexpected reply from the storage server"); }
	}

	private static String text(Element e, String tag) { NodeList n = e.getElementsByTagName(tag); return n.getLength() == 0 ? "" : n.item(0).getTextContent(); }
	private static String blankTo(String s, String d) { return s == null || s.isBlank() ? d : s.trim(); }
	private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
	private static byte[] md5(byte[] b) { try { return java.security.MessageDigest.getInstance("MD5").digest(b); } catch (Exception e) { throw new IllegalStateException(e); } }
	private static byte[] hexToBytes(String h) { byte[] o = new byte[h.length() / 2]; for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return o; }
}
