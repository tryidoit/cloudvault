package com.cloudvault.backup;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.incremental.Incremental;
import com.cloudvault.incremental.Manifest;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.HashUtil;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

/**
 * Creates backups: fingerprint -> copy to temp -> ZIP -> verify -> SHA-256 -> store -> queue upload.
 * The original world is only READ. Must be called on the worker thread (after the game thread flushed the world).
 */
public class BackupManager {
	/** @param comment text the player typed for this backup (null = none); it is stored in the backup record and inside the zip */
	public record Request(Path worldDir, String worldName, String worldId, BackupType type, String minecraftVersion, String comment) {
		public Request(Path worldDir, String worldName, String worldId, BackupType type, String minecraftVersion) { this(worldDir, worldName, worldId, type, minecraftVersion, null); }
		public Request withComment(String c) { return new Request(worldDir, worldName, worldId, type, minecraftVersion, c); }
	}

	/** Name of the comment file placed at the top of the backup zip. It is not extracted when the backup is restored. */
	public static final String COMMENT_FILE = "cloudvault-comment.txt";

	public static String cleanComment(String text) {
		String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("\\n{3,}", "\n\n").strip();
		return t.length() > 500 ? t.substring(0, 500) : t;
	}

	private final BackupStore store;
	private final CloudVaultConfig config;
	private final CloudManager cloud;
	private final RetentionManager retention;
	private final NotificationSink sink;
	private final BackupQueue queue;
	private volatile Activity activity = new Activity();
	public void setActivity(Activity a) { this.activity = a; }
	private volatile com.cloudvault.mirror.MirrorManager mirror; private volatile com.cloudvault.alerts.AlertManager alerts;
	public void setMirror(com.cloudvault.mirror.MirrorManager m) { this.mirror = m; }
	public void setAlerts(com.cloudvault.alerts.AlertManager a) { this.alerts = a; }

	public BackupManager(BackupStore store, CloudVaultConfig config, CloudManager cloud, RetentionManager retention, BackupQueue queue, NotificationSink sink) {
		this.store = store; this.config = config; this.cloud = cloud; this.retention = retention; this.queue = queue; this.sink = sink;
	}

	/** Queue a backup (non-blocking). Key prevents piling up duplicate periodic/gameplay requests. */
	public void submit(Request r, Runnable beforeStart) {
		String key = r.type == BackupType.MANUAL ? null : "backup:" + r.worldId + ":" + r.type.category;
		queue.submit(r.type.priority, key, () -> {
			if (beforeStart != null) beforeStart.run();
			run(r);
		});
	}

	/** Runs a backup now (blocking). Returns null when skipped or failed; never throws. */
	public BackupMetadata run(Request r) {
		if (r.type.category != BackupType.Category.SAFETY && config.isWorldDisabled(r.worldId)) return null; // CloudVault is off for this world
		Path tmpDir = store.temp.resolve(UUID.randomUUID().toString());
		try {
			activity.set(Activity.Phase.PREPARING, r.worldName);
			BackupSettings s = config.effective(r.worldId);
			String fp = FileUtil.fingerprint(r.worldDir);
			boolean dedupe = r.type == BackupType.MANUAL ? s.manual.avoidDuplicates : (r.type.category != BackupType.Category.SAFETY && s.periodic.avoidDuplicates);
			if (dedupe) {
				List<BackupMetadata> same = store.forWorld(r.worldId).stream().filter(m -> m.backupType.category == r.type.category).toList();
				if (!same.isEmpty() && fp.equals(same.get(0).contentFingerprint)) return null; // world unchanged
			}
			checkDiskSpace(r.worldDir);
			Path copy = tmpDir.resolve("world");
			activity.set(Activity.Phase.COPYING, r.worldName);
			copyWithRetry(r.worldDir, copy);
			return finish(r, new Work(tmpDir, copy, fp));
		} catch (Throwable e) {
			failed(r, tmpDir, e);
			return null;
		} finally {
			activity.idle();
		}
	}

	/** A world copy that is waiting to be compressed, verified, saved and uploaded. */
	public static final class Work {
		final Path tmpDir, copy; final String fp;
		Work(Path tmpDir, Path copy, String fp) { this.tmpDir = tmpDir; this.copy = copy; this.fp = fp; }
	}

	/**
	 * Copies the world right now (blocking) and returns the work to finish later in the background. For the moment before a world is
	 * opened, when it must not change while we copy it. Throws if the copy fails; nothing is left behind then.
	 */
	public Work copyNow(Request r) throws IOException {
		Path tmpDir = store.temp.resolve(UUID.randomUUID().toString());
		try {
			activity.set(Activity.Phase.COPYING, r.worldName);
			checkDiskSpace(r.worldDir);
			Path copy = tmpDir.resolve("world");
			copyWithRetry(r.worldDir, copy);
			return new Work(tmpDir, copy, FileUtil.fingerprint(r.worldDir));
		} catch (Throwable e) {
			FileUtil.deleteQuietly(tmpDir);
			if (e instanceof IOException io) throw io;
			throw new IOException(e.getMessage(), e);
		} finally { activity.idle(); }
	}

	/** Queues the rest of a backup started with {@link #copyNow}. */
	public void submitFinish(Request r, Work w) { queue.submit(r.type.priority, null, () -> { finish(r, w); }); }

	private void failed(Request r, Path tmpDir, Throwable e) {
		String why = ErrorText.friendly(e);
		CloudVault.LOGGER.error("Backup failed for {}: {}", r.worldName, ErrorText.redact(e.toString()));
		activity.failed("Backup of " + r.worldName + " failed: " + why);
		if (config.notifications) sink.notify(NotificationSink.Level.ERROR, "Backup failed", why);
		if (alerts != null) alerts.send(com.cloudvault.alerts.AlertManager.Event.BACKUP_FAILED, "Backup of " + r.worldName + " failed", why);
		FileUtil.deleteQuietly(tmpDir);
	}

	/** Compress, verify, hash, save, then upload / apply retention. Never throws. */
	private BackupMetadata finish(Request r, Work w) {
		try {
			BackupSettings s = config.effective(r.worldId);
			BackupMetadata m = new BackupMetadata();
			m.worldName = r.worldName; m.worldId = r.worldId; m.backupType = r.type;
			m.timestamp = System.currentTimeMillis();
			m.zipFileName = CloudFileName.build(m.timestamp, m.backupType, m.worldName, m.backupId);
			m.minecraftVersion = r.minecraftVersion; m.cloudVaultVersion = CloudVault.VERSION;
			m.contentFingerprint = w.fp;
			String comment = cleanComment(r.comment());
			if (!comment.isEmpty()) { // goes into the zip (and so into the cloud copy) next to the world files
				m.comment = comment;
				Files.writeString(w.copy.resolve(COMMENT_FILE), comment, java.nio.charset.StandardCharsets.UTF_8);
			}

			Built built = buildZip(r, s, m, w);
			Path zip = built.zip;
			activity.set(Activity.Phase.VERIFYING, r.worldName);
			BackupVerifier.verifyZip(zip);
			HashUtil.Hashes h = HashUtil.hashFile(zip);
			m.sha256 = h.sha256(); m.md5 = h.md5(); m.fileSize = h.size();
			m.localVerified = true;
			m.uploadStatus = (config.cloudEnabled && cloud.hasProvider()) ? UploadStatus.PENDING : UploadStatus.LOCAL_ONLY;

			FileUtil.moveSafely(zip, store.zipPath(m));
			if (built.manifestJson != null) try { store.saveManifest(m.backupId, built.manifestJson); } catch (IOException e) { CloudVault.LOGGER.warn("Could not save the file list of a backup: {}", e.getMessage()); } // not needed for a restore: it is inside the zip too
			store.save(m); // from here on the backup survives a crash or restart as "pending"
			FileUtil.deleteQuietly(w.tmpDir);
			activity.succeeded("Backup of " + r.worldName);
			if (r.type == BackupType.MANUAL && config.notifications) sink.notify(NotificationSink.Level.SUCCESS, "CloudVault", "Backup saved on this computer");
			warnIfLowDisk(m);

			if (mirror != null) mirror.enqueue(m.backupId); // second destination: independent of the first, never delays it
			if (m.uploadStatus == UploadStatus.PENDING) cloud.enqueueUpload(m.backupId);
			else retention.apply(m.worldId, m.backupType.category, retentionFor(s, m.backupType.category), s.advanced, null);
			return m;
		} catch (Throwable e) {
			failed(r, w.tmpDir, e);
			return null;
		} finally {
			activity.idle();
		}
	}

	private static final class Built { final Path zip; final String manifestJson; Built(Path zip, String manifestJson) { this.zip = zip; this.manifestJson = manifestJson; } }

	/**
	 * Makes the zip: an incremental one (only what changed) when {@link Incremental} allows it, else a full one. ANY problem while
	 * preparing or checking an incremental backup falls back to a full backup, so incremental can never cost a backup.
	 */
	private Built buildZip(Request r, BackupSettings s, BackupMetadata m, Work w) throws IOException {
		Manifest scanned = null; Incremental.Decision dec = null;
		if (s.incremental.enabled && r.type.category != BackupType.Category.SAFETY) {
			try {
				activity.set(Activity.Phase.PREPARING, r.worldName);
				scanned = Manifest.scan(w.copy, m.backupId, Incremental.SKIP);
				dec = Incremental.decide(store, s, r.type, store.forWorld(r.worldId), scanned, m.backupId, m.timestamp);
			} catch (Throwable t) { CloudVault.LOGGER.warn("Incremental check skipped, making a full backup: {}", ErrorText.redact(String.valueOf(t.getMessage()))); dec = null; }
		}
		activity.set(Activity.Phase.COMPRESSING, r.worldName);
		if (dec != null && dec.isDelta()) {
			Path dz = null;
			try {
				BackupMetadata parent = dec.parent();
				String deltaName = CloudFileName.build(m.timestamp, m.backupType, m.worldName, m.backupId, true);
				dz = w.tmpDir.resolve(deltaName);
				java.util.Set<String> paths = new java.util.TreeSet<>(dec.changed());
				if (Files.exists(w.copy.resolve(COMMENT_FILE))) paths.add(COMMENT_FILE);
				String json = dec.manifest().toJson();
				BackupCompressor.zipSelected(w.copy, paths, java.util.Map.of(Manifest.ZIP_ENTRY, json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), dz, config.compressionLevel);
				BackupVerifier.verifyZip(dz);
				long size = Files.size(dz);
				if (dec.estFullBytes() > 0 && size > dec.estFullBytes() * s.incremental.maxChangedPercent / 100L * 12 / 10) throw new IOException("incremental file turned out too large");
				m.zipFileName = deltaName; m.kind = "DELTA"; m.parentId = parent.backupId;
				m.baseId = parent.isDelta() && parent.baseId != null ? parent.baseId : parent.backupId;
				m.baseTimestamp = parent.baseTimestamp > 0 ? parent.baseTimestamp : parent.timestamp;
				m.dependsOn = Incremental.dependsOn(dec.manifest(), m.backupId);
				m.chainDepth = parent.chainDepth + 1; m.changedFiles = dec.changed().size(); m.totalFiles = dec.manifest().files.size(); m.worldBytes = dec.manifest().totalBytes;
				return new Built(dz, json);
			} catch (Throwable t) {
				CloudVault.LOGGER.warn("Incremental backup not used, making a full backup: {}", ErrorText.redact(String.valueOf(t.getMessage())));
				if (dz != null) FileUtil.deleteQuietly(dz);
				m.kind = null; m.parentId = null; m.baseId = null; m.dependsOn = null; m.chainDepth = 0;
				m.zipFileName = CloudFileName.build(m.timestamp, m.backupType, m.worldName, m.backupId);
			}
		}
		Path zip = w.tmpDir.resolve(m.fileName());
		String json = null;
		if (scanned != null) { // a full backup that also records its file list, so the next one can be incremental
			json = scanned.toJson();
			Files.writeString(w.copy.resolve(Manifest.ZIP_ENTRY), json, java.nio.charset.StandardCharsets.UTF_8);
			m.baseTimestamp = m.timestamp; m.chainDepth = 0; m.totalFiles = scanned.files.size(); m.worldBytes = scanned.totalBytes;
		}
		BackupCompressor.zipDirectory(w.copy, zip, config.compressionLevel);
		return new Built(zip, json);
	}

	/** Free space on the drive that holds the backups, or -1 if unknown. */
	public long freeBytes() {
		try { return Files.getFileStore(store.root).getUsableSpace(); } catch (IOException e) { return -1; }
	}

	public static final long LOW_DISK_BYTES = 2L * 1024 * 1024 * 1024;

	private void warnIfLowDisk(BackupMetadata m) {
		long free = freeBytes();
		if (free >= 0 && free < Math.max(LOW_DISK_BYTES, 3 * m.fileSize) && config.notifications)
			sink.notify(NotificationSink.Level.WARNING, "CloudVault", "Low disk space on this computer (" + com.cloudvault.cloud.StorageManager.human(free) + " free)");
	}

	public static int retentionFor(BackupSettings s, BackupType.Category c) {
		return switch (c) {
			case SESSION -> s.session.retention;
			case PERIODIC -> s.periodic.retention;
			case GAMEPLAY -> s.gameplay.retention;
			case MANUAL -> s.manual.retention;
			case EVENT -> s.smart.retention;
			case SAFETY -> BackupSettings.KEEP_ALL;
		};
	}

	private void copyWithRetry(Path src, Path dst) throws IOException {
		IOException last = null;
		for (int i = 0; i < 3; i++) {
			try { FileUtil.copyTree(src, dst); return; }
			catch (IOException e) { // a file may be mid-write; wait and redo the whole copy
				last = e; FileUtil.deleteQuietly(dst);
				try { Thread.sleep(700); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
			}
		}
		throw last;
	}

	private void checkDiskSpace(Path worldDir) throws IOException {
		long size;
		try (var s = Files.walk(worldDir)) {
			size = s.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum();
		}
		long free = Files.getFileStore(store.temp).getUsableSpace();
		if (free < size * 2 + 100L * 1024 * 1024) throw new IOException("Not enough free disk space for a backup");
	}

}
