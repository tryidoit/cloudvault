package com.cloudvault.incremental;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The complete file list of a world at one moment: size, SHA-256, CRC-32 and WHICH BACKUP holds each file's content (src).
 * It is stored inside the backup zip (cloudvault-manifest.json) and next to the local records. A restore rebuilds the world from it and
 * checks every single file against its SHA-256, so an incremental restore is either exactly right or it stops before touching the world.
 */
public final class Manifest {
	public static final String ZIP_ENTRY = "cloudvault-manifest.json";
	private static final Gson GSON = new Gson();

	public static final class Entry {
		public long size, crc;
		public String sha, src;
		public Entry() {}
		Entry(long size, long crc, String sha, String src) { this.size = size; this.crc = crc; this.sha = sha; this.src = src; }
	}

	public int format = 1;
	public String backupId, parentId;
	public long createdAt, totalBytes;
	public Map<String, Entry> files = new TreeMap<>();

	/** Reads every file of the (copied) world once and records it. All content is attributed to {@code backupId}. */
	public static Manifest scan(Path dir, String backupId, java.util.Set<String> skipTopLevel) throws IOException {
		Manifest m = new Manifest();
		m.backupId = backupId; m.createdAt = System.currentTimeMillis();
		byte[] buf = new byte[256 * 1024];
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
				if (!a.isRegularFile()) return FileVisitResult.CONTINUE;
				String rel = dir.relativize(f).toString().replace('\\', '/');
				if (skipTopLevel.contains(rel)) return FileVisitResult.CONTINUE;
				MessageDigest sha; CRC32 crc = new CRC32(); long size = 0;
				try { sha = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
				try (InputStream in = Files.newInputStream(f)) {
					int n;
					while ((n = in.read(buf)) > 0) { sha.update(buf, 0, n); crc.update(buf, 0, n); size += n; }
				}
				m.files.put(rel, new Entry(size, crc.getValue(), HexFormat.of().formatHex(sha.digest()), backupId));
				m.totalBytes += size;
				return FileVisitResult.CONTINUE;
			}
		});
		return m;
	}

	public String toJson() { return GSON.toJson(this); }

	public static Manifest fromJson(String json) {
		try {
			Manifest m = GSON.fromJson(json, Manifest.class);
			if (m == null || m.files == null || m.backupId == null) return null;
			for (var e : m.files.entrySet()) {
				Entry x = e.getValue();
				if (x == null || x.sha == null || x.sha.length() != 64 || x.src == null || e.getKey().isEmpty()) return null;
			}
			return m;
		} catch (RuntimeException e) { return null; }
	}

	/** The manifest stored inside a backup zip, or null if the zip has none. */
	public static Manifest readFromZip(Path zip) throws IOException {
		try (ZipFile z = new ZipFile(zip.toFile())) {
			ZipEntry e = z.getEntry(ZIP_ENTRY);
			if (e == null) return null;
			try (InputStream in = z.getInputStream(e)) { return fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
		}
	}
}
