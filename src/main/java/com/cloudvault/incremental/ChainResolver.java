package com.cloudvault.incremental;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rebuilds the COMPLETE world zip of an incremental backup from the zips that hold its files, verifying every file's SHA-256. */
public final class ChainResolver {
	private ChainResolver() {}

	/**
	 * @param ownZip the incremental backup's own (verified) zip; it carries the manifest
	 * @param zipOf  gives the verified own zip of another backup by id (may download it); throws when it is not available
	 * @return {@code out}, a normal full world zip (no manifest or comment file inside)
	 */
	public static Path materialize(Path ownZip, String ownId, Function<String, Path> zipOf, Path out) throws IOException {
		Manifest m = Manifest.readFromZip(ownZip);
		if (m == null) throw new IOException("This incremental backup has no file list, so it cannot be put back together");
		Map<String, ZipFile> zips = new HashMap<>();
		Files.createDirectories(out.toAbsolutePath().getParent());
		Path part = out.resolveSibling(out.getFileName() + ".part");
		try {
			zips.put(ownId, new ZipFile(ownZip.toFile()));
			for (var e : m.files.entrySet()) if (!zips.containsKey(e.getValue().src)) zips.put(e.getValue().src, new ZipFile(zipOf.apply(e.getValue().src).toFile()));
			byte[] buf = new byte[256 * 1024];
			try (OutputStream os = Files.newOutputStream(part); ZipOutputStream zos = new ZipOutputStream(os)) {
				zos.setLevel(Deflater.BEST_SPEED);
				for (var e : m.files.entrySet()) {
					String path = e.getKey(); Manifest.Entry want = e.getValue();
					ZipFile z = zips.get(want.src);
					ZipEntry src = z.getEntry(path);
					if (src == null) throw new IOException("A needed file is missing from an earlier backup (" + path + ")");
					MessageDigest sha; try { sha = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
					long size = 0;
					ZipEntry dst = new ZipEntry(path); dst.setTime(src.getTime());
					zos.putNextEntry(dst);
					try (InputStream in = z.getInputStream(src)) {
						int n;
						while ((n = in.read(buf)) > 0) { sha.update(buf, 0, n); zos.write(buf, 0, n); size += n; }
					}
					zos.closeEntry();
					if (size != want.size || !HexFormat.of().formatHex(sha.digest()).equals(want.sha))
						throw new IOException("The backup chain is damaged at " + path + " - nothing was restored");
				}
			}
			Files.move(part, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return out;
		} catch (IOException | RuntimeException e) {
			try { Files.deleteIfExists(part); } catch (IOException ignored) { /* temp */ }
			throw e;
		} finally {
			for (ZipFile z : zips.values()) try { z.close(); } catch (IOException ignored) { /* read only */ }
		}
	}
}
