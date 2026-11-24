package com.cloudvault.cloud;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.backup.BackupStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/** Storage numbers and cleanup tools for the "Storage" screen. Nothing here deletes a backup that is not safely in the cloud. */
public class StorageManager {
	public record Report(long localBytes, int localFiles, long cloudBytes, int verified, int pending, int failed, int localOnly,
	                     int encrypted, long tempBytes, CloudProvider.StorageInfo account) {}

	private final BackupStore store;
	private final CloudManager cloud;

	public StorageManager(BackupStore store, CloudManager cloud) { this.store = store; this.cloud = cloud; }

	/** Blocking (disk scan, and a quick cloud call if connected): call from a background thread. */
	public Report report() {
		long localBytes = 0; int localFiles = 0;
		int ver = 0, pen = 0, fail = 0, lo = 0, enc = 0; long cloudBytes = 0;
		for (BackupMetadata m : store.all()) {
			if (m.localFilePresent && Files.exists(store.zipPath(m))) { localFiles++; localBytes += m.fileSize; }
			switch (m.uploadStatus) {
				case UPLOADED_VERIFIED -> { ver++; cloudBytes += m.cloudSize > 0 ? m.cloudSize : m.fileSize; if (m.encrypted) enc++; }
				case PENDING, UPLOADING -> pen++;
				case FAILED -> fail++;
				case LOCAL_ONLY -> lo++;
			}
		}
		CloudProvider.StorageInfo acct = null;
		CloudProvider p = cloud.provider();
		if (p != null && p.isAuthenticated()) { try { acct = p.storageInfo(); } catch (Exception ignored) { /* optional */ } }
		return new Report(localBytes, localFiles, cloudBytes, ver, pen, fail, lo, enc, dirSize(store.temp), acct);
	}

	/** Deletes the local ZIP of every backup that is verified in the cloud (frees disk space; they re-download when needed). Returns bytes freed. */
	public long clearLocalCopiesOfUploaded() {
		long freed = 0;
		for (BackupMetadata m : store.all()) {
			if (m.uploadStatus != UploadStatus.UPLOADED_VERIFIED || !m.localFilePresent) continue;
			try {
				Path z = store.zipPath(m);
				long sz = Files.exists(z) ? Files.size(z) : 0;
				Files.deleteIfExists(z);
				m.localFilePresent = false; store.save(m);
				freed += sz;
			} catch (IOException ignored) { /* skip this one */ }
		}
		return freed;
	}

	/** Removes leftover temporary files (only safe when no backup is running). Returns bytes freed. */
	public long cleanTemp() {
		long before = dirSize(store.temp);
		store.cleanTemp();
		return Math.max(0, before - dirSize(store.temp));
	}

	private static long dirSize(Path dir) {
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } }).sum();
		} catch (IOException e) { return 0; }
	}

	public static String human(long b) {
		if (b < 0) return "unlimited";
		String[] u = {"B", "KB", "MB", "GB", "TB"};
		double v = b; int i = 0;
		while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
		return (i == 0 ? Long.toString(b) : String.format(java.util.Locale.ROOT, "%.1f", v)) + " " + u[i];
	}
}
