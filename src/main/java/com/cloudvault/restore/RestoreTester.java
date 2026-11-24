package com.cloudvault.restore;

import com.cloudvault.backup.Activity;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.backup.BackupVerifier;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Proves that a backup can really be restored: re-checks the archive, extracts it (with the same safe extractor a real restore uses)
 * into a temporary folder, and looks inside the result. Never touches any world. The temp folder is always removed.
 */
public class RestoreTester {
	public enum Outcome { OK, FAILED, SKIPPED }
	public record Result(Outcome outcome, String message) {}

	private final BackupStore store;
	private final CloudManager cloud;
	private volatile Activity activity = new Activity();

	public RestoreTester(BackupStore store, CloudManager cloud) { this.store = store; this.cloud = cloud; }
	public void setActivity(Activity a) { this.activity = a; }

	/** @param allowDownload when false, a backup that is only in the cloud is skipped instead of downloaded (the automatic test never downloads). */
	public Result test(BackupMetadata m, boolean allowDownload) {
		Path tmp = store.temp.resolve("restoretest-" + UUID.randomUUID());
		activity.set(Activity.Phase.TESTING, m.worldName);
		try {
			Path zip;
			if (!m.isDelta() && Files.exists(store.zipPath(m)) && m.sha256 != null) zip = store.zipPath(m);
			else if (m.isDelta() && cloud.isChainLocal(m)) zip = cloud.obtainVerifiedZip(m); // put back together from local files; every file is checked
			else if (allowDownload) zip = cloud.obtainVerifiedZip(m);
			else return remember(m, new Result(Outcome.SKIPPED, "No copy on this computer to test (it is only in the cloud)"));

			if (!m.isDelta()) { // (an incremental backup is checked file by file while it is put back together)
				try { BackupVerifier.verifyChecksum(zip, m.sha256 == null ? "" : m.sha256); }
				catch (IOException e) { if (m.sha256 != null) return remember(m, new Result(Outcome.FAILED, "The copy on this computer is damaged")); }
			}
			BackupVerifier.verifyZip(zip);

			long unpacked = 0;
			try (ZipFile zf = new ZipFile(zip.toFile())) {
				var en = zf.entries();
				while (en.hasMoreElements()) { ZipEntry e = en.nextElement(); if (e.getSize() > 0) unpacked += e.getSize(); }
			}
			Files.createDirectories(store.temp);
			if (Files.getFileStore(store.temp).getUsableSpace() < unpacked + 256L * 1024 * 1024)
				return remember(m, new Result(Outcome.SKIPPED, "Not enough free disk space for a restore test"));

			RestoreManager.extractSafely(zip, tmp);
			String problem = inspect(tmp);
			return remember(m, problem == null ? new Result(Outcome.OK, "OK") : new Result(Outcome.FAILED, problem));
		} catch (Throwable e) {
			return remember(m, new Result(Outcome.FAILED, ErrorText.friendly(e)));
		} finally {
			FileUtil.deleteQuietly(tmp);
			activity.idle();
		}
	}

	private Result remember(BackupMetadata m, Result r) {
		try {
			store.update(m.backupId, c -> { c.testedAt = System.currentTimeMillis(); c.testResult = r.outcome() == Outcome.OK ? "OK" : (r.outcome() == Outcome.SKIPPED ? "Skipped: " : "FAILED: ") + r.message(); });
		} catch (IOException ignored) { /* the result is still returned to the caller */ }
		return r;
	}

	/** Null when the extracted world looks right, otherwise what is wrong. */
	static String inspect(Path world) throws IOException {
		Path level = world.resolve("level.dat");
		if (!Files.isRegularFile(level) || Files.size(level) == 0) return "level.dat is missing";
		if (!LevelInfo.looksValid(level)) return "level.dat cannot be read";
		int[] checked = {0};
		String[] bad = {null};
		try (Stream<Path> s = Files.walk(world)) {
			for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".mca") && Files.isRegularFile(f))::iterator) {
				if (checked[0]++ >= 6) break;
				long size = Files.size(p);
				if (size != 0 && (size % 4096 != 0 || size < 8192)) { bad[0] = "A region file is damaged (" + p.getFileName() + ")"; break; }
				if (size >= 8192) try (InputStream in = Files.newInputStream(p)) { if (in.readNBytes(8192).length < 8192) { bad[0] = "A region file is cut short"; break; } }
			}
		}
		return bad[0];
	}
}
