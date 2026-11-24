package com.cloudvault.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupCompressor {
	private BackupCompressor() {}

	/** Zips only the listed files (paths relative to {@code dir}, '/' separators) plus extra in-memory entries. */
	public static void zipSelected(Path dir, java.util.Collection<String> paths, java.util.Map<String, byte[]> extra, Path zip, int level) throws IOException {
		Files.createDirectories(zip.toAbsolutePath().getParent());
		try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(os)) {
			byte[] buf = new byte[256 * 1024];
			zos.setLevel(Math.max(Deflater.NO_COMPRESSION, Math.min(Deflater.BEST_COMPRESSION, level)));
			for (String rel : new java.util.TreeSet<>(paths)) {
				Path f = dir.resolve(rel);
				BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
				ZipEntry e = new ZipEntry(rel); e.setTime(a.lastModifiedTime().toMillis());
				zos.putNextEntry(e);
				try (InputStream in = Files.newInputStream(f)) { int n; while ((n = in.read(buf)) > 0) zos.write(buf, 0, n); }
				zos.closeEntry();
			}
			for (var x : extra.entrySet()) { zos.putNextEntry(new ZipEntry(x.getKey())); zos.write(x.getValue()); zos.closeEntry(); }
		}
	}

	/** Zips the contents of {@code dir} (entries relative to dir, '/' separators) into {@code zip}. */
	public static void zipDirectory(Path dir, Path zip, int level) throws IOException {
		Files.createDirectories(zip.toAbsolutePath().getParent());
		try (OutputStream os = Files.newOutputStream(zip);
		     ZipOutputStream zos = new ZipOutputStream(os)) {
			byte[] buf = new byte[256 * 1024];
			zos.setLevel(Math.max(Deflater.NO_COMPRESSION, Math.min(Deflater.BEST_COMPRESSION, level)));
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
					String name = dir.relativize(f).toString().replace('\\', '/');
					ZipEntry e = new ZipEntry(name);
					e.setTime(a.lastModifiedTime().toMillis());
					zos.putNextEntry(e);
					try (InputStream in = Files.newInputStream(f)) {
						int n;
						while ((n = in.read(buf)) > 0) zos.write(buf, 0, n); // big buffer: far fewer system calls than Files.copy
					}
					zos.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}
}
