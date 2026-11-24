package com.cloudvault.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/** All low-level filesystem helpers live here. */
public final class FileUtil {
	private FileUtil() {}

	/** Turns any world name into a safe single path segment. */
	public static String safeName(String name) {
		String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
		s = s.replaceAll("^\\.+", "_");
		if (s.isEmpty()) s = "world";
		return s.length() > 80 ? s.substring(0, 80) : s;
	}

	/** Atomic move where supported, falls back to a normal move. Never overwrites silently. */
	public static void moveSafely(Path from, Path to) throws IOException {
		Files.createDirectories(to.toAbsolutePath().getParent());
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to);
		}
	}

	/** Copies a directory tree, skipping the lock file. Unreadable transient files are reported, not fatal for lock files. */
	public static void copyTree(Path src, Path dst) throws IOException {
		Files.walkFileTree(src, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
				Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
				if (file.getFileName().toString().equals("session.lock") || !a.isRegularFile()) return FileVisitResult.CONTINUE;
				Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Deletes a directory tree; failures are swallowed (cleanup must never fail a backup). */
	public static void deleteQuietly(Path dir) {
		if (dir == null || !Files.exists(dir)) return;
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> {
				try { Files.deleteIfExists(p); } catch (IOException ignored) {}
			});
		} catch (IOException ignored) {}
	}

	/**
	 * Cheap content fingerprint of a world folder (paths + sizes + mtimes), used for duplicate detection. Streaming and order-independent:
	 * no big strings, no sorting, one stat per file (attributes come from the directory walk).
	 */
	public static String fingerprint(Path dir) throws IOException {
		java.security.MessageDigest md;
		try { md = java.security.MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
		long[] acc = new long[3]; // sum, xor, count
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
				if (!a.isRegularFile() || f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
				md.reset();
				md.update(dir.relativize(f).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				md.update((byte) '|');
				long size = a.size(), mt = a.lastModifiedTime().toMillis();
				for (int i = 0; i < 8; i++) { md.update((byte) (size >>> (8 * i))); md.update((byte) (mt >>> (8 * i))); }
				java.nio.ByteBuffer d = java.nio.ByteBuffer.wrap(md.digest());
				acc[0] += d.getLong(); acc[1] ^= d.getLong(8); acc[2]++;
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; } // vanished while walking
		});
		return "v2:" + Long.toHexString(acc[0]) + Long.toHexString(acc[1]) + ":" + acc[2];
	}

	/** Removes leftover ".tmp" files (half-written atomic writes from a crash). Only call at startup. */
	public static void sweepTmp(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
		} catch (IOException ignored) {}
	}

	private static Path tmpFor(Path file) {
		return file.resolveSibling(file.getFileName() + "." + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + ".tmp");
	}

	/** Writes text atomically (temp file + move) so a crash never leaves half a config/metadata file. */
	public static void writeAtomic(Path file, String content) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = tmpFor(file); // unique per call: two threads saving the same file can never mix their data
		try { Files.writeString(tmp, content); moveReplace(tmp, file); }
		finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
	}

	/** Like writeAtomic, but the file is readable by its owner only where the file system supports that (tokens, keys). */
	public static void writeSecret(Path file, String content) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path tmp = tmpFor(file);
		try {
			try {
				Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
			} catch (UnsupportedOperationException e) { /* Windows: the user profile folder is already private */ }
			Files.writeString(tmp, content);
			moveReplace(tmp, file);
		} finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
	}

	private static void moveReplace(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
