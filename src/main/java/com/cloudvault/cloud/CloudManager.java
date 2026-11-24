package com.cloudvault.cloud;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.cloud.CloudProvider.StorageInfo;
import com.cloudvault.crypto.BackupCrypto;
import com.cloudvault.crypto.EncryptionService;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.HashUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Upload -> verify -> mark valid -> retention, with persistent pending state and exponential backoff retries. */
public class CloudManager {
	private volatile CloudProvider provider; // the ACTIVE provider (new uploads go here)
	private volatile com.cloudvault.mirror.MirrorManager mirror;
	private volatile com.cloudvault.alerts.AlertManager alerts;
	private volatile java.util.function.BooleanSupplier playing = () -> false;
	private final java.util.Set<String> uploadNow = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public void setMirror(com.cloudvault.mirror.MirrorManager m) { this.mirror = m; }
	public void setAlerts(com.cloudvault.alerts.AlertManager a) { this.alerts = a; }
	/** True while a world is open (used by "do not upload while playing"). */
	public void setPlaying(java.util.function.BooleanSupplier p) { this.playing = p; }
	private final java.util.Map<String, CloudProvider> providers = new java.util.concurrent.ConcurrentHashMap<>();
	private final BackupStore store;
	private final CloudVaultConfig config;
	private final BackupQueue queue;
	private final RetentionManager retention;
	private final NotificationSink sink;
	private final EncryptionService encryption;
	private volatile com.cloudvault.backup.Activity activity = new com.cloudvault.backup.Activity();
	public void setActivity(com.cloudvault.backup.Activity a) { this.activity = a; }
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "CloudVault-Retry"); t.setDaemon(true); return t;
	});

	public CloudManager(BackupStore store, CloudVaultConfig config, BackupQueue queue, RetentionManager retention, NotificationSink sink, EncryptionService encryption) {
		this.store = store; this.config = config; this.queue = queue; this.retention = retention; this.sink = sink; this.encryption = encryption;
		timer.scheduleWithFixedDelay(this::kickWaiting, 2, 2, TimeUnit.MINUTES);
	}

	/** Why uploads are on hold right now (Settings > Upload controls), or null when they may go. */
	public String uploadHold() {
		var u = config.upload;
		if (u.paused) return "uploads are paused";
		if (u.onlyWhenNotPlaying && playing.getAsBoolean()) return "you are playing; uploads start after you leave the world";
		if (u.windowEnabled) {
			int h = java.time.LocalTime.now().getHour(), a = u.windowStartHour, b = u.windowEndHour;
			boolean inside = a == b || (a < b ? h >= a && h < b : h >= a || h < b);
			if (!inside) return String.format("outside the upload hours (%02d:00-%02d:00)", a, b);
		}
		return null;
	}

	/** Starts the uploads that were waiting for the hold to end. Called every couple of minutes and when you leave a world. */
	public void kickWaiting() {
		if (uploadHold() != null) return;
		for (BackupMetadata m : store.all()) if (m.uploadStatus == UploadStatus.PENDING && m.localFilePresent && m.nextAttemptAt <= System.currentTimeMillis() && !config.isWorldDisabled(m.worldId)) enqueueUpload(m.backupId);
	}

	/** Uploads one backup now even if uploads are on hold. */
	public void uploadNowIgnoringHold(String backupId) { uploadNow.add(backupId); retryNow(backupId); }

	public void setProvider(CloudProvider p) { this.provider = p; if (p != null) providers.put(p.id(), p); }
	/** Makes a provider known so existing backups stored there stay reachable even when another provider is active. */
	public void registerProvider(CloudProvider p) { providers.put(p.id(), p); }
	/** The provider that holds this backup's cloud copy (null if it is not available). */
	public CloudProvider providerFor(BackupMetadata m) { return providers.get(m.provider()); }
	public CloudProvider provider() { return provider; }
	public boolean hasProvider() { return provider != null; }

	/** Called at startup: anything not verified in the cloud is re-queued (survives restarts). */
	public void resumePending() {
		for (BackupMetadata m : store.all()) {
			if (m.uploadStatus == UploadStatus.UPLOADING) { m.uploadStatus = UploadStatus.PENDING; save(m); }
			if (m.uploadStatus == UploadStatus.PENDING && m.localFilePresent) enqueueUpload(m.backupId);
		}
	}

	/** Manual "retry now" for pending/failed backups. */
	public void retryNow(String backupId) {
		store.find(backupId).ifPresent(m -> {
			if (m.uploadStatus == UploadStatus.FAILED) { m.uploadStatus = UploadStatus.PENDING; m.uploadAttempts = 0; save(m); }
			enqueueUpload(backupId);
		});
	}

	public void enqueueUpload(String backupId) {
		int prio = store.find(backupId).map(m -> m.backupType.priority).orElse(5);
		queue.submit(prio, "upload:" + backupId, () -> upload(backupId));
	}

	private void upload(String backupId) {
		BackupMetadata m = store.find(backupId).orElse(null);
		if (m == null || m.uploadStatus == UploadStatus.UPLOADED_VERIFIED) return;
		if (config.isWorldDisabled(m.worldId)) return; // CloudVault is off for this world: leave it as it is until it is switched on again
		CloudProvider p = provider;
		if (p == null || !config.cloudEnabled) { m.uploadStatus = UploadStatus.PENDING; m.lastError = "Cloud not configured"; save(m); return; }
		String hold = uploadNow.remove(backupId) ? null : uploadHold();
		if (hold != null) { m.uploadStatus = UploadStatus.PENDING; m.lastError = "Waiting: " + hold; save(m); return; } // stays safe on this computer; kickWaiting() starts it later
		Throttle.setLimitKBps(config.upload.speedLimitKBps);
		Path zip = store.zipPath(m);
		Path encFile = null;
		try {
			if (!Files.exists(zip)) throw new CloudException(CloudException.Kind.OTHER, "Local backup file is missing");
			BackupVerifier.verifyChecksum(zip, m.sha256); // never upload a damaged file
			boolean encrypting = config.encryptionEnabled;
			if (encrypting && !encryption.isSetUp()) // stays pending until the user sets up (or turns off) encryption
				throw new CloudException(CloudException.Kind.AUTH, "Encryption is turned on but no key is set up");
			m.uploadStatus = UploadStatus.UPLOADING; m.lastError = null; save(m);
			activity.set(com.cloudvault.backup.Activity.Phase.UPLOADING, m.worldName);
			boolean loud = m.backupType == com.cloudvault.backup.BackupType.MANUAL; // automatic backups only speak up when something is wrong
			if (loud && config.notifications) sink.notify(NotificationSink.Level.INFO, "CloudVault", "Uploading backup...");

			Path send = zip; String remoteName = m.fileName();
			long expectSize = m.fileSize; String expectMd5 = m.md5;
			Map<String, String> props = new java.util.HashMap<>(Map.of("backupId", m.backupId, "backupType", m.backupType.name(), "timestamp", Long.toString(m.timestamp),
				"worldId", m.worldId, "mcVersion", m.minecraftVersion == null ? "unknown" : m.minecraftVersion.substring(0, Math.min(40, m.minecraftVersion.length()))));
			if (m.isDelta()) props.put("kind", "DELTA");
			if (encrypting) { // encrypt locally; the cloud only ever receives the encrypted copy (and no plaintext checksum)
				encFile = store.temp.resolve(m.backupId + ".upload.cvenc");
				encryption.encrypt(zip, encFile);
				HashUtil.Hashes eh = HashUtil.hashFile(encFile);
				send = encFile; remoteName = remoteName + ".cvenc"; expectSize = eh.size(); expectMd5 = eh.md5();
				props.put("encrypted", "1");
			} else props.put("sha256", m.sha256);

			CloudProvider.RemoteFile rf = p.uploadBackup(send, remoteName, FileUtil.safeName(m.worldName) + " [" + m.worldId.substring(0, 8) + "]", props);
			activity.set(com.cloudvault.backup.Activity.Phase.VERIFYING_CLOUD, m.worldName);
			if (rf.size() != expectSize) throw new CloudException(CloudException.Kind.OTHER, "Cloud copy has wrong size");
			if (rf.md5() != null && !rf.md5().equalsIgnoreCase(expectMd5)) throw new CloudException(CloudException.Kind.OTHER, "Cloud copy checksum mismatch");
			m.cloudProvider = p.id(); m.encrypted = encrypting; m.cloudSize = expectSize; m.cloudMd5 = expectMd5; m.missingInCloud = false;

			m.remoteId = rf.id(); m.uploadStatus = UploadStatus.UPLOADED_VERIFIED; m.uploadAttempts = 0; m.lastError = null;
			save(m);
			activity.succeeded("Uploaded backup of " + m.worldName);
			if (loud && config.notifications) sink.notify(NotificationSink.Level.SUCCESS, "CloudVault", "Backup uploaded and verified");
			retention.apply(m.worldId, m.backupType.category, BackupManager.retentionFor(config.effective(m.worldId), m.backupType.category), config.effective(m.worldId).advanced, p);
			trimCache(m.worldId);
			checkQuota(p);
		} catch (Throwable e) {
			handleFailure(m, e);
		} finally {
			activity.idle();
			if (encFile != null) try { Files.deleteIfExists(encFile); } catch (IOException ignored) {}
		}
	}

	private void handleFailure(BackupMetadata m, Throwable e) {
		m.uploadAttempts++;
		m.lastError = com.cloudvault.util.ErrorText.friendly(e);
		activity.failed("Upload of " + m.worldName + ": " + m.lastError);
		boolean retryable = !(e instanceof CloudException ce) || ce.retryable();
		if (e instanceof IOException && !(e instanceof CloudException) && m.lastError.startsWith("Checksum")) retryable = false; // damaged local file
		if (retryable && config.retryFailedUploads) {
			m.uploadStatus = UploadStatus.PENDING;
			long delay = Math.min(3600, (long) config.retryDelaySeconds << Math.min(10, m.uploadAttempts - 1));
			m.nextAttemptAt = System.currentTimeMillis() + delay * 1000;
			timer.schedule(() -> enqueueUpload(m.backupId), delay, TimeUnit.SECONDS);
			if (config.notifications) sink.notify(NotificationSink.Level.WARNING, "CloudVault", m.lastError.startsWith("No internet") ? "No internet: backup is pending and will upload later" : "Upload failed - will retry");
		} else {
			m.uploadStatus = (e instanceof CloudException ce && ce.kind == CloudException.Kind.AUTH) ? UploadStatus.PENDING : UploadStatus.FAILED;
			if (config.notifications) sink.notify(NotificationSink.Level.ERROR, "CloudVault", (e instanceof CloudException ce2 && ce2.kind == CloudException.Kind.AUTH) ? "Cloud sign-in needed: open CloudVault > Cloud" : "Upload failed: " + m.lastError);
		}
		save(m);
		if (alerts != null && (m.uploadStatus == UploadStatus.FAILED || m.uploadAttempts >= 3)) alerts.send(com.cloudvault.alerts.AlertManager.Event.UPLOAD_FAILED, "Upload of " + m.worldName + " failing", m.lastError == null ? "unknown error" : m.lastError);
		CloudVault.LOGGER.warn("Upload of {} failed ({}): {}", m.backupId, m.uploadStatus, com.cloudvault.util.ErrorText.redact(m.lastError));
	}

	/** Local cache: keep the newest N verified-uploaded zips per world, drop the others locally (cloud copy stays). */
	public void trimCache(String worldId) {
		int keep = config.localCacheKeep;
		List<BackupMetadata> uploaded = store.forWorld(worldId).stream()
			.filter(x -> x.uploadStatus == UploadStatus.UPLOADED_VERIFIED && x.localFilePresent).toList();
		// the newest N stay on this computer together with the backups they need (so restoring them never has to download a chain)
		java.util.Set<String> keepIds = store.closure(uploaded.stream().limit(keep).map(x -> x.backupId).toList());
		for (int i = keep; i < uploaded.size(); i++) {
			BackupMetadata x = uploaded.get(i);
			if (keepIds.contains(x.backupId)) continue;
			try { Files.deleteIfExists(store.zipPath(x)); x.localFilePresent = false; save(x); } catch (IOException ignored) {}
		}
	}

	/** Copies/downloads a backup as a verified ZIP to {@code target}. Blocking: use a background thread. */
	public void exportZip(BackupMetadata m, Path target) throws IOException {
		Path src = obtainVerifiedZip(m);
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path part = target.resolveSibling(target.getFileName() + ".part");
		Files.copy(src, part, StandardCopyOption.REPLACE_EXISTING);
		FileUtil.moveSafely(part, target);
	}

	/**
	 * Returns a local, checksum-verified zip of the COMPLETE world for the backup, downloading what is needed. For an incremental backup
	 * the world is put back together from the backups that hold its files (every file is checked); the result is a temporary file that
	 * the caller only reads. For a normal backup it is the stored zip itself.
	 */
	public Path obtainVerifiedZip(BackupMetadata m) throws IOException {
		if (!m.isDelta()) return obtainOwnZip(m);
		Path own = obtainOwnZip(m);
		sweepMaterialized();
		Path out = store.temp.resolve(m.backupId + ".world-" + System.nanoTime() + ".mat.zip");
		try { return com.cloudvault.incremental.ChainResolver.materialize(own, m.backupId, id -> {
			BackupMetadata d = store.find(id).orElse(null);
			if (d == null) throw new UncheckedIOException(new IOException("This backup needs an earlier backup that is no longer available"));
			try { return obtainOwnZip(d); } catch (IOException e) { throw new UncheckedIOException(new IOException("An earlier backup this one needs could not be loaded: " + e.getMessage(), e)); }
		}, out); } catch (UncheckedIOException e) { throw e.getCause(); }
	}

	/** Rebuilt worlds are temporary: keep only the newest so a restore test loop cannot fill the disk. */
	private void sweepMaterialized() {
		try (java.util.stream.Stream<Path> s = Files.list(store.temp)) {
			List<Path> old = s.filter(p -> p.getFileName().toString().endsWith(".mat.zip")).sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).skip(1).toList();
			for (Path p : old) Files.deleteIfExists(p);
		} catch (IOException ignored) { /* temp */ }
	}

	/** True when every zip an incremental backup needs is already on this computer (a restore test can run without downloading). */
	public boolean isChainLocal(BackupMetadata m) {
		if (!m.isDelta()) return Files.exists(store.zipPath(m));
		java.util.Set<String> ids = new java.util.LinkedHashSet<>(); ids.add(m.backupId);
		if (m.dependsOn != null) ids.addAll(m.dependsOn);
		for (String id : ids) { BackupMetadata d = store.find(id).orElse(null); if (d == null || !Files.exists(store.zipPath(d))) return false; }
		return true;
	}

	/** One backup's own zip (for an incremental backup: only what changed), verified; downloaded if it is not on this computer. */
	public Path obtainOwnZip(BackupMetadata m) throws IOException {
		Path local = store.zipPath(m);
		if (Files.exists(local) && m.sha256 != null) {
			try { BackupVerifier.verifyChecksum(local, m.sha256); return local; }
			catch (IOException bad) { if (m.remoteId == null) throw bad; /* else re-download below */ }
		}
		CloudProvider p = providerFor(m);
		boolean haveMirror = mirror != null && m.mirrorRemoteId != null;
		if ((m.remoteId == null || p == null) && !haveMirror) throw new CloudException(CloudException.Kind.OTHER, "Backup is not available locally or in the cloud");
		Path part = store.temp.resolve(m.backupId + ".download.part");
		Path plain = store.temp.resolve(m.backupId + ".decrypted.part");
		try {
			Path result = part;
			boolean fromMirror = false;
			if (m.remoteId != null && p != null) {
				try { p.downloadBackup(m.remoteId, part); }
				catch (IOException e) { if (!haveMirror) throw e; fromMirror = true; CloudVault.LOGGER.warn("First copy unavailable, using the second destination: {}", com.cloudvault.util.ErrorText.redact(String.valueOf(e.getMessage()))); }
			} else fromMirror = true;
			if (fromMirror) { mirror.download(m, plain); result = plain; } // already decrypted
			else if (m.encrypted || BackupCrypto.looksEncrypted(part)) { // decrypt locally; GCM authentication detects damage and wrong keys
				encryption.decrypt(part, plain);
				result = plain;
			}
			if (m.sha256 == null || m.sha256.isBlank()) { // imported backup: nothing to compare with yet, so check the archive and remember its checksum
				BackupVerifier.verifyZip(result);
				HashUtil.Hashes h = HashUtil.hashFile(result);
				m.sha256 = h.sha256(); m.md5 = h.md5(); m.fileSize = h.size(); m.localVerified = true;
			} else {
				BackupVerifier.verifyChecksum(result, m.sha256);
				BackupVerifier.verifyZip(result);
			}
			FileUtil.moveSafely(result, local);
			m.localFilePresent = true; save(m);
			return local;
		} finally {
			try { Files.deleteIfExists(part); Files.deleteIfExists(plain); } catch (IOException ignored) {}
		}
	}

	/**
	 * Downloads one cloud file, decrypts it if needed and checks that it is a sound zip. Records NOTHING (no metadata, no cache):
	 * used by world import and cloud sync. The caller must delete the returned file.
	 */
	public Path downloadPlainZip(CloudProvider p, String remoteId, String remoteName, String expectSha256) throws IOException {
		Files.createDirectories(store.temp);
		String id = java.util.UUID.randomUUID().toString();
		Path part = store.temp.resolve(id + ".dl.part"), plain = store.temp.resolve(id + ".dl.zip");
		try {
			p.downloadBackup(remoteId, part);
			Path result = part;
			if ((remoteName != null && remoteName.endsWith(".cvenc")) || BackupCrypto.looksEncrypted(part)) { encryption.decrypt(part, plain); result = plain; }
			BackupVerifier.verifyZip(result);
			if (expectSha256 != null && expectSha256.matches("[0-9a-fA-F]{64}")) BackupVerifier.verifyChecksum(result, expectSha256.toLowerCase(java.util.Locale.ROOT));
			Path out = store.temp.resolve(id + ".ready.zip");
			Files.move(result, out);
			return out;
		} finally {
			try { Files.deleteIfExists(part); Files.deleteIfExists(plain); } catch (IOException ignored) { /* temp leftovers are swept at start */ }
		}
	}

	public EncryptionService encryption() { return encryption; }

	/** Manual delete (user confirmed in GUI): cloud first, then local. */
	public void deleteBackup(BackupMetadata m) throws IOException {
		if (store.isRequired(m.backupId)) throw new IOException("Other backups need this one to be restored. Delete those first (or let retention remove this one when they are gone).");
		if (m.remoteId != null) {
			CloudProvider p = providerFor(m);
			if (p == null) throw new CloudException(CloudException.Kind.OTHER, "Connect to the cloud to delete this backup");
			p.deleteBackup(m.remoteId);
		}
		if (mirror != null) mirror.deleteCopy(m);
		store.deleteLocal(m);
	}

	private volatile long quotaUsed = -1, quotaTotal = -1, quotaAt = 0;

	/** Percent of the account storage used as of the last check, or -1 if unknown (cloud has no quota, or not checked yet). */
	public int quotaPercent() { return quotaTotal > 0 && quotaUsed >= 0 ? (int) Math.min(100, quotaUsed * 100 / quotaTotal) : -1; }

	/** At most once an hour, after an upload: remember how full the account is and warn at 90%. */
	private void checkQuota(CloudProvider p) {
		if (!"google_drive".equals(p.id()) || System.currentTimeMillis() - quotaAt < 3_600_000L) return;
		quotaAt = System.currentTimeMillis();
		try {
			StorageInfo i = p.storageInfo();
			if (i == null) return;
			quotaUsed = i.used(); quotaTotal = i.total();
			int pc = quotaPercent();
			if (pc >= 90 && config.notifications) sink.notify(NotificationSink.Level.WARNING, "CloudVault", "Cloud storage is " + pc + "% full");
			if (pc >= 90 && alerts != null) alerts.send(com.cloudvault.alerts.AlertManager.Event.LOW_SPACE, "Cloud storage almost full", "Your cloud storage is " + pc + "% full. New backups may fail.");
		} catch (Exception ignored) { /* optional information */ }
	}

	public record RefreshResult(int cloudFiles, long cloudBytes, int missing, int unknown) {}

	/** Re-reads the cloud listing and reconciles it with local records. Backups that vanished from the cloud are re-uploaded if a local copy exists. */
	public RefreshResult refresh() throws IOException {
		CloudProvider p = provider;
		if (p == null || !p.isAuthenticated()) throw new CloudException(CloudException.Kind.AUTH, "Not connected to the cloud");
		List<CloudProvider.RemoteFile> remote = p.listAllBackups();
		java.util.Set<String> ids = new java.util.HashSet<>();
		long bytes = 0;
		for (CloudProvider.RemoteFile f : remote) { ids.add(f.id()); bytes += f.size(); }
		int missing = 0;
		java.util.Set<String> known = new java.util.HashSet<>();
		for (BackupMetadata m : store.all()) {
			if (m.remoteId == null || !m.provider().equals(p.id())) continue; // only backups held by the active provider
			known.add(m.remoteId);
			if (m.uploadStatus == UploadStatus.UPLOADED_VERIFIED && !ids.contains(m.remoteId)) {
				missing++; m.missingInCloud = true; m.remoteId = null;
				if (m.localFilePresent) { m.uploadStatus = UploadStatus.PENDING; m.lastError = "Missing in the cloud - uploading again"; save(m); enqueueUpload(m.backupId); }
				else { m.uploadStatus = UploadStatus.FAILED; m.lastError = "Missing in the cloud and no local copy"; save(m); }
			}
		}
		int unknown = 0;
		for (String id : ids) if (!known.contains(id)) unknown++;
		return new RefreshResult(remote.size(), bytes, missing, unknown);
	}

	/** Re-queues every pending or failed backup (the "retry all" cleanup tool). Returns how many. */
	public int retryAll() {
		int n = 0;
		for (BackupMetadata m : store.all()) {
			if ((m.uploadStatus == UploadStatus.FAILED || m.uploadStatus == UploadStatus.PENDING) && m.localFilePresent) { retryNow(m.backupId); n++; }
		}
		return n;
	}

	/** Lost the local key file? Fetch one encrypted backup from the cloud and unlock with the password or recovery key. */
	public void unlockEncryption(String passwordOrRecoveryKey) throws IOException {
		BackupMetadata src = store.all().stream().filter(x -> x.encrypted && x.remoteId != null && providerFor(x) != null).findFirst().orElse(null);
		CloudProvider p = src == null ? null : providerFor(src);
		if (src == null || p == null) throw new CloudException(CloudException.Kind.OTHER, "No encrypted backup in the cloud to unlock with");
		Path part = store.temp.resolve("unlock.download.part");
		try { p.downloadBackup(src.remoteId, part); encryption.unlockFrom(part, passwordOrRecoveryKey); }
		finally { try { Files.deleteIfExists(part); } catch (IOException ignored) {} }
	}

	private void save(BackupMetadata m) { try { store.save(m); } catch (IOException e) { CloudVault.LOGGER.error("Could not save metadata: {}", e.getMessage()); } }

	public void shutdown() { timer.shutdownNow(); }
}
