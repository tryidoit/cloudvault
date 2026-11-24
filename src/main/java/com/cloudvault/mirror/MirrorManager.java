package com.cloudvault.mirror;

import com.cloudvault.CloudVault;
import com.cloudvault.alerts.AlertManager;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.crypto.BackupCrypto;
import com.cloudvault.crypto.EncryptionService;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.HashUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Second destination. Every backup is ALSO copied to another place (second cloud, NAS, a Dropbox/OneDrive folder). The first destination
 * never waits for it and is never affected by it: a failing mirror only means "not mirrored yet" and is retried. Deleting a backup deletes
 * both copies. A restore that cannot get the first copy falls back to this one.
 */
public class MirrorManager {
	private static final int MAX_ATTEMPTS = 6;
	private final BackupStore store;
	private final CloudVaultConfig config;
	private final CloudManager cloud;
	private final BackupQueue queue;
	private final EncryptionService encryption;
	private final NotificationSink sink;
	private volatile CloudProvider provider;
	private volatile AlertManager alerts;
	private final Path orphanFile;
	private final Set<String> orphans = new LinkedHashSet<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CloudVault-Mirror"); t.setDaemon(true); return t; });
	private volatile long lastBackfill;

	public MirrorManager(BackupStore store, CloudVaultConfig config, CloudManager cloud, BackupQueue queue, EncryptionService encryption, NotificationSink sink) {
		this.store = store; this.config = config; this.cloud = cloud; this.queue = queue; this.encryption = encryption; this.sink = sink;
		this.orphanFile = store.auth.getParent().resolve("mirror-orphans.json");
		try { if (Files.exists(orphanFile)) { List<String> l = new Gson().fromJson(Files.readString(orphanFile), new TypeToken<List<String>>() {}.getType()); if (l != null) orphans.addAll(l); } } catch (Exception ignored) { /* start empty */ }
	}

	/** Builds the second destination from the settings: a folder or S3 here, a second Google Drive by the client (returns null for it). */
	public static CloudProvider build(CloudVaultConfig c, com.cloudvault.util.SecretStore secrets) {
		return switch (c.mirror.type) {
			case "local" -> new com.cloudvault.cloud.LocalFolderProvider("mirror_folder", () -> c.mirror.localPath);
			case "s3" -> new com.cloudvault.cloud.s3.S3Provider(() -> new com.cloudvault.cloud.s3.S3Provider.Cfg(c.mirror.s3Endpoint, c.mirror.s3Region, c.mirror.s3Bucket, c.mirror.s3AccessKey, secrets.get("mirror.s3.secret"), c.mirror.s3PathStyle));
			default -> null;
		};
	}

	public void setProvider(CloudProvider p) { this.provider = p; }

	/** After a setting changed: rebuild the folder/S3 destination (a second Google account is handled by the client). */
	public void setProviderIfLocal(CloudVaultConfig c, com.cloudvault.util.SecretStore secrets) {
		if (!c.mirror.type.equals("google_drive")) { provider = build(c, secrets); if (c.mirror.enabled) backfillSoon(); }
	}

	private void backfillSoon() { long now = System.currentTimeMillis(); if (now - lastBackfill > 30_000L) { lastBackfill = now; timer.schedule(this::backfill, 3, TimeUnit.SECONDS); } }

	public void setAlerts(AlertManager a) { this.alerts = a; }
	public CloudProvider provider() { return provider; }
	public boolean active() { return config.mirror.enabled && provider != null; }

	/** Queues the copy of one backup (no-op when the second destination is off or the backup is already there). */
	public void enqueue(String backupId) {
		if (!active()) return;
		queue.submit(6, "mirror:" + backupId, () -> copy(backupId));
	}

	/** Queues every backup that has no second copy yet. Called at start and when the second destination is switched on. */
	public void backfill() {
		if (!active()) return;
		retryOrphans();
		for (BackupMetadata m : store.all()) if (needsCopy(m)) { if (m.mirrorAttempts >= MAX_ATTEMPTS) saveQuiet(m.backupId, x -> x.mirrorAttempts = 0); enqueue(m.backupId); }
	}

	private void saveQuiet(String id, java.util.function.Consumer<BackupMetadata> change) { try { store.update(id, change); } catch (IOException e) { CloudVault.LOGGER.warn("Could not save mirror state: {}", e.getMessage()); } }

	private boolean needsCopy(BackupMetadata m) {
		if (m.mirrorRemoteId != null) return false;
		if (config.isWorldDisabled(m.worldId) && m.backupType.category != BackupType.Category.SAFETY) return false;
		return m.uploadStatus == UploadStatus.UPLOADED_VERIFIED || m.uploadStatus == UploadStatus.LOCAL_ONLY || m.localFilePresent;
	}

	private void copy(String backupId) {
		BackupMetadata m = store.find(backupId).orElse(null);
		CloudProvider p = provider;
		if (m == null || p == null || !config.mirror.enabled || !needsCopy(m)) return;
		Path enc = null;
		try {
			Path zip = cloud.obtainOwnZip(m);
			boolean encrypting = config.encryptionEnabled;
			if (encrypting && !encryption.isSetUp()) throw new CloudException(CloudException.Kind.AUTH, "Encryption is turned on but no key is set up");
			Path send = zip; String name = m.fileName(); long size = m.fileSize; String md5 = m.md5;
			Map<String, String> props = new HashMap<>(Map.of("backupId", m.backupId, "backupType", m.backupType.name(), "timestamp", Long.toString(m.timestamp), "worldId", m.worldId));
			if (m.isDelta()) props.put("kind", "DELTA");
			if (encrypting) {
				enc = store.temp.resolve(m.backupId + ".mirror.cvenc");
				encryption.encrypt(zip, enc);
				HashUtil.Hashes h = HashUtil.hashFile(enc);
				send = enc; name += ".cvenc"; size = h.size(); md5 = h.md5(); props.put("encrypted", "1");
			} else if (m.sha256 != null) props.put("sha256", m.sha256);
			CloudProvider.RemoteFile rf = p.uploadBackup(send, name, FileUtil.safeName(m.worldName) + " [" + m.worldId.substring(0, 8) + "]", props);
			if (rf.size() != size) throw new CloudException(CloudException.Kind.OTHER, "Second copy has wrong size");
			if (md5 != null && rf.md5() != null && !rf.md5().equalsIgnoreCase(md5)) throw new CloudException(CloudException.Kind.OTHER, "Second copy checksum mismatch");
			final boolean e2 = encrypting; final long s2 = size;
			saveQuiet(backupId, x -> { x.mirrorRemoteId = rf.id(); x.mirrorProvider = p.id(); x.mirrorSize = s2; x.mirrorEncrypted = e2; x.mirrorError = null; x.mirrorAttempts = 0; });
		} catch (Throwable e) {
			String why = ErrorText.friendly(e);
			CloudVault.LOGGER.warn("Second destination copy failed: {}", ErrorText.redact(why));
			int attempts = m.mirrorAttempts + 1;
			saveQuiet(backupId, x -> { x.mirrorAttempts = attempts; x.mirrorError = why; });
			if (attempts < MAX_ATTEMPTS) timer.schedule(() -> enqueue(backupId), Math.min(3600, 60L << Math.min(6, attempts)), TimeUnit.SECONDS);
			else {
				if (config.notifications) sink.notify(NotificationSink.Level.WARNING, "Second destination", "Could not copy a backup there: " + why);
				if (alerts != null) alerts.send(AlertManager.Event.UPLOAD_FAILED, "Second destination failing", "Backups are not reaching the second destination: " + why);
			}
		} finally { if (enc != null) try { Files.deleteIfExists(enc); } catch (IOException ignored) { /* temp */ } }
	}

	/** Deletes the second copy of a backup that is being deleted. A copy that cannot be deleted now is remembered and removed later. */
	public void deleteCopy(BackupMetadata m) {
		if (m.mirrorRemoteId == null) return;
		CloudProvider p = provider;
		try {
			if (p == null || !p.id().equals(m.mirrorProvider)) throw new IOException("second destination not available");
			p.deleteBackup(m.mirrorRemoteId);
		} catch (Exception e) {
			synchronized (orphans) { orphans.add(m.mirrorProvider + "|" + m.mirrorRemoteId); saveOrphans(); }
		}
	}

	private void saveOrphans() { try { Files.writeString(orphanFile, new Gson().toJson(orphans)); } catch (IOException ignored) { /* best effort */ } }

	public void retryOrphans() {
		CloudProvider p = provider;
		if (p == null) return;
		synchronized (orphans) {
			for (Iterator<String> it = orphans.iterator(); it.hasNext(); ) {
				String o = it.next(); int i = o.indexOf('|');
				if (i < 0 || !o.substring(0, i).equals(p.id())) continue;
				try { p.deleteBackup(o.substring(i + 1)); it.remove(); } catch (Exception e) { if (String.valueOf(e.getMessage()).contains("notfound")) it.remove(); }
			}
			saveOrphans();
		}
	}

	/** Downloads the second copy of a backup (decrypted if needed) into {@code target}. Used when the first copy cannot be loaded. */
	public void download(BackupMetadata m, Path target) throws IOException {
		CloudProvider p = provider;
		if (p == null || m.mirrorRemoteId == null || !p.id().equals(m.mirrorProvider)) throw new CloudException(CloudException.Kind.OTHER, "No second copy available");
		Path part = target.resolveSibling(target.getFileName() + ".mirror.part");
		try {
			p.downloadBackup(m.mirrorRemoteId, part);
			if (m.mirrorEncrypted || BackupCrypto.looksEncrypted(part)) { encryption.decrypt(part, target); } else Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} finally { try { Files.deleteIfExists(part); } catch (IOException ignored) { /* temp */ } }
	}

	public void shutdown() { timer.shutdownNow(); }
}
