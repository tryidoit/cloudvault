package com.cloudvault.restore;

import com.cloudvault.backup.*;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.util.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verify -> safety backup -> extract safely to temp -> move into saves. Blocking: run on a background thread. */
public class RestoreManager {
	private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024 * 1024; // zip-bomb guard
	private static final int MAX_ENTRIES = 2_000_000;

	private final BackupStore store;
	private final CloudManager cloud;
	private final BackupManager backups;
	private volatile Activity activity = new Activity();
	public void setActivity(Activity a) { this.activity = a; }

	public RestoreManager(BackupStore store, CloudManager cloud, BackupManager backups) {
		this.store = store; this.cloud = cloud; this.backups = backups;
	}

	/** Restore as a new world folder next to the others. Returns the new folder. */
	public Path restoreAsNew(BackupMetadata m, Path savesDir, BackupManager.Request safetyOfCurrent) throws IOException {
		activity.set(Activity.Phase.RESTORING, m.worldName);
		try { return restoreAsNewImpl(m, savesDir, safetyOfCurrent); } finally { activity.idle(); }
	}

	private Path restoreAsNewImpl(BackupMetadata m, Path savesDir, BackupManager.Request safetyOfCurrent) throws IOException {
		Path zip = cloud.obtainVerifiedZip(m);
		BackupVerifier.verifyZip(zip);
		makeSafety(safetyOfCurrent);
		Path tmp = store.temp.resolve("restore-" + UUID.randomUUID());
		try {
			extractSafely(zip, tmp);
			Path target = uniqueFolder(savesDir, FileUtil.safeName(m.worldName) + " - Restored");
			FileUtil.moveSafely(tmp, target);
			return target;
		} finally {
			FileUtil.deleteQuietly(tmp);
		}
	}

	/** Replace an existing world. The old folder is moved aside (never deleted) into cloudvault/replaced/. World must NOT be loaded. */
	public Path replaceWorld(BackupMetadata m, Path worldDir, BackupManager.Request safetyOfCurrent) throws IOException {
		activity.set(Activity.Phase.RESTORING, m.worldName);
		try { return replaceWorldImpl(m, worldDir, safetyOfCurrent); } finally { activity.idle(); }
	}

	private Path replaceWorldImpl(BackupMetadata m, Path worldDir, BackupManager.Request safetyOfCurrent) throws IOException {
		Path zip = cloud.obtainVerifiedZip(m);
		BackupVerifier.verifyZip(zip);
		if (safetyOfCurrent == null || !Files.isDirectory(worldDir)) throw new IOException("Current world not found");
		if (makeSafety(safetyOfCurrent) == null) throw new IOException("Safety backup failed - restore cancelled, your world was not touched");
		return swapIn(zip, worldDir);
	}

	public record ResetResult(BackupMetadata restoredFrom, BackupMetadata beforeReset) {}

	/** The backup "reset" would use: the newest non-safety backup that can actually be restored. Null if there is none. */
	public BackupMetadata latestUsable(String worldId) {
		return store.forWorld(worldId).stream()
			.filter(m -> m.backupType.category != BackupType.Category.SAFETY)
			.filter(m -> (m.localFilePresent && m.localVerified) || m.uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED)
			.findFirst().orElse(null);
	}

	/**
	 * Resets a world to its latest backup. Order matters: (1) find and fully verify the backup, so a bad backup stops us before anything
	 * changes, (2) make a separate BEFORE RESET backup of the world as it is now, (3) swap the folder (the old one is kept in cloudvault/replaced).
	 * The world must NOT be loaded. Blocking: call from a background thread.
	 */
	public ResetResult resetToLatest(String worldId, Path worldDir, String worldName, String minecraftVersion) throws IOException {
		activity.set(Activity.Phase.RESTORING, worldName);
		try { return resetToLatestImpl(worldId, worldDir, worldName, minecraftVersion); } finally { activity.idle(); }
	}

	private ResetResult resetToLatestImpl(String worldId, Path worldDir, String worldName, String minecraftVersion) throws IOException {
		if (!Files.isDirectory(worldDir)) throw new IOException("World folder not found");
		BackupMetadata latest = latestUsable(worldId);
		if (latest == null) throw new IOException("This world has no backup to reset to");
		Path zip = cloud.obtainVerifiedZip(latest);
		BackupVerifier.verifyZip(zip);
		BackupMetadata before = backups.run(new BackupManager.Request(worldDir, worldName, worldId, BackupType.PRE_RESET, minecraftVersion));
		if (before == null) throw new IOException("Could not make the BEFORE RESET backup - your world was not touched");
		swapIn(zip, worldDir);
		return new ResetResult(latest, before);
	}

	/** Extracts a verified zip as a NEW world folder in saves (nothing else is recorded or changed). Returns the folder. */
	public Path extractAsNewWorld(Path zip, String worldName, Path savesDir) throws IOException {
		BackupVerifier.verifyZip(zip);
		Path tmp = store.temp.resolve("import-" + UUID.randomUUID());
		try {
			extractSafely(zip, tmp);
			Files.createDirectories(savesDir);
			Path target = uniqueFolder(savesDir, FileUtil.safeName(worldName));
			FileUtil.moveSafely(tmp, target);
			return target;
		} finally { FileUtil.deleteQuietly(tmp); }
	}

	/** Replaces an existing world folder with the zip's content. The old folder is kept in cloudvault/replaced. The world must not be loaded. */
	public Path replaceFromZip(Path zip, Path worldDir) throws IOException {
		BackupVerifier.verifyZip(zip);
		if (!Files.isDirectory(worldDir)) throw new IOException("World folder not found");
		return swapIn(zip, worldDir);
	}

	private Path swapIn(Path zip, Path worldDir) throws IOException {
		Path tmp = store.temp.resolve("restore-" + UUID.randomUUID());
		try {
			extractSafely(zip, tmp);
			Path aside = uniqueFolder(store.root.resolve("replaced"), worldDir.getFileName() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
			Files.createDirectories(aside.getParent());
			Files.move(worldDir, aside);
			try {
				FileUtil.moveSafely(tmp, worldDir);
			} catch (IOException e) {
				Files.move(aside, worldDir); // roll back
				throw e;
			}
			return worldDir;
		} finally {
			FileUtil.deleteQuietly(tmp);
		}
	}

	private BackupMetadata makeSafety(BackupManager.Request current) throws IOException {
		if (current == null) return null; // restoring as new world with nothing loaded: nothing to protect
		BackupMetadata s = backups.run(new BackupManager.Request(current.worldDir(), current.worldName(), current.worldId(), BackupType.SAFETY, current.minecraftVersion()));
		if (s == null) throw new IOException("Safety backup failed - restore cancelled, your world was not touched");
		return s;
	}

	private static Path uniqueFolder(Path parent, String base) {
		Path p = parent.resolve(base);
		for (int i = 2; Files.exists(p); i++) p = parent.resolve(base + " " + i);
		return p;
	}

	/** Extracts with path-traversal, symlink-free, size and count protection. */
	private static final java.util.Set<String> RESERVED = java.util.Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	/** Windows device names and names ending in a space or dot behave unpredictably there; no Minecraft world contains them. */
	static boolean reservedName(String entry) {
		for (String seg : entry.split("/")) {
			if (seg.isEmpty()) continue;
			if (seg.equals(".") || seg.equals("..") || seg.endsWith(" ") || seg.endsWith(".")) return true;
			int dot = seg.indexOf('.');
			if (RESERVED.contains((dot < 0 ? seg : seg.substring(0, dot)).toUpperCase(java.util.Locale.ROOT))) return true;
		}
		return false;
	}

	public static void extractSafely(Path zip, Path dest) throws IOException {
		Files.createDirectories(dest);
		Path root = dest.toAbsolutePath().normalize();
		long total = 0; int count = 0;
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> en = zf.entries();
			byte[] buf = new byte[256 * 1024];
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (++count > MAX_ENTRIES) throw new IOException("Archive has too many files");
				String name = e.getName();
				if (name.equals(BackupManager.COMMENT_FILE) || name.equals(com.cloudvault.incremental.Manifest.ZIP_ENTRY)) continue; // the comment belongs to the backup, not to the world folder
				if (reservedName(name)) throw new IOException("Unsafe path in archive: " + name);
				if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.contains(":") || name.contains("\u0000")) // backslashes mean path separators on Windows
					throw new IOException("Unsafe path in archive: " + name);
				Path out = root.resolve(name).normalize();
				if (!out.startsWith(root) || out.equals(root) && !e.isDirectory()) throw new IOException("Unsafe path in archive: " + name);
				if (e.isDirectory()) { Files.createDirectories(out); continue; }
				Files.createDirectories(out.getParent());
				try (InputStream in = zf.getInputStream(e); var os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
					int n;
					while ((n = in.read(buf)) > 0) {
						total += n;
						if (total > MAX_TOTAL_BYTES) throw new IOException("Archive is unreasonably large");
						os.write(buf, 0, n);
					}
				}
			}
		}
		if (!Files.exists(root.resolve("level.dat"))) throw new IOException("Restored data has no level.dat");
	}
}
