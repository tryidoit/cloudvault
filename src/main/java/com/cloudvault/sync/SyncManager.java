package com.cloudvault.sync;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.*;
import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.cloud.CloudProvider.RemoteFile;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.config.WorldSettings;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.restore.RestoreManager;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.HashUtil;
import com.cloudvault.util.WorldUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Cloud sync. A synced world is uploaded to the cloud sync folder when you leave it (the newest N copies are kept). When Minecraft starts,
 * synced worlds that this computer does not have are added, and a world that is OLDER here than in the cloud is updated (the old folder is
 * kept in cloudvault/replaced). If the world changed on BOTH computers nothing is overwritten: the cloud version is added as "... (cloud copy)".
 * Every world is tied together by its sync id, which is the same on every computer.
 */
public class SyncManager {
	private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/** What a pull did. {@code error} is null when the cloud could be read. */
	public record PullResult(int downloaded, int updated, int copies, int skipped, String error) {
		public String summary() {
			if (error != null) return error;
			if (downloaded + updated + copies == 0) return skipped > 0 ? "Nothing changed (" + skipped + " skipped)" : "Everything is up to date";
			return downloaded + " added, " + updated + " updated" + (copies > 0 ? ", " + copies + " added as a copy (changed on both computers)" : "");
		}
	}

	private final BackupStore store;
	private final CloudVaultConfig config;
	private final CloudManager cloud;
	private final WorldUtil worlds;
	private final RestoreManager restore;
	private final BackupQueue queue;
	private final NotificationSink sink;
	private final Runnable saveConfig;
	private volatile Activity activity = new Activity();
	private volatile String status = "Not synced yet this session";
	private volatile com.cloudvault.lock.LockManager lock;
	private volatile com.cloudvault.alerts.AlertManager alerts;
	private volatile java.util.function.BiConsumer<String, WorldSettings> onWorldAdded = (id, ws) -> {};
	public void setLock(com.cloudvault.lock.LockManager l) { this.lock = l; }
	public void setAlerts(com.cloudvault.alerts.AlertManager a) { this.alerts = a; }
	/** Called with (syncId, settings) when a synced world is added to this computer (the account file may hold settings for it). */
	public void setOnWorldAdded(java.util.function.BiConsumer<String, WorldSettings> c) { this.onWorldAdded = c; }
	private final Object pullLock = new Object();

	public SyncManager(BackupStore store, CloudVaultConfig config, CloudManager cloud, WorldUtil worlds, RestoreManager restore, BackupQueue queue, NotificationSink sink, Runnable saveConfig) {
		this.store = store; this.config = config; this.cloud = cloud; this.worlds = worlds; this.restore = restore; this.queue = queue; this.sink = sink; this.saveConfig = saveConfig;
	}

	public void setActivity(Activity a) { this.activity = a; }
	/** One line for the settings page: what the last sync did. */
	public String status() { return status; }

	// ---------------------------------------------------------------- upload

	/** Upload while the world stays open (does not touch the world lock). */
	public void requestPushWhileOpen(String worldId, String worldName, Path worldDir) { requestPush(worldId, worldName, worldDir, false); }

	/** Marks the world as "waiting for upload" and queues the upload. Cheap. Call when the world is closed (saved): the world lock is released when it is done. */
	public void requestPush(String worldId, String worldName, Path worldDir) { requestPush(worldId, worldName, worldDir, true); }

	private void requestPush(String worldId, String worldName, Path worldDir, boolean leaving) {
		if (worldId == null || worldDir == null || !config.isWorldSynced(worldId)) return;
		WorldSettings ws = config.worldSettings(worldId);
		ws.syncDirty = true;
		if (worldName != null && !worldName.isBlank()) ws.lastKnownName = worldName;
		saveConfig.run();
		queue.submit(2, "sync:" + worldId, () -> { boolean ok = push(worldId, worldName, worldDir); if (leaving && lock != null) lock.release(ok); });
	}

	/** Copies, zips, uploads and prunes. Blocking; never throws. Returns true when the cloud now has this version. */
	public boolean push(String worldId, String worldName, Path worldDir) {
		WorldSettings ws = config.worldSettings(worldId);
		Path tmpDir = store.temp.resolve("sync-" + UUID.randomUUID());
		try {
			CloudProvider p = cloud.provider();
			if (p == null || !p.isAuthenticated() || !config.cloudEnabled) throw new CloudException(CloudException.Kind.AUTH, "Not connected to the cloud");
			if (!Files.isRegularFile(worldDir.resolve("level.dat"))) throw new IOException("World folder not found");
			boolean encrypting = config.encryptionEnabled;
			if (encrypting && !cloud.encryption().isSetUp()) throw new CloudException(CloudException.Kind.AUTH, "Encryption is turned on but no key is set up");
			if (ws.syncId == null || !UUID_RE.matcher(ws.syncId).matches()) { ws.syncId = UUID.randomUUID().toString(); saveConfig.run(); }
			if (Files.getFileStore(store.root).getUsableSpace() < 300L * 1024 * 1024) throw new IOException("Not enough free disk space for a sync copy");

			activity.set(Activity.Phase.PREPARING, worldName);
			String fp = FileUtil.fingerprint(worldDir);
			Path copy = tmpDir.resolve("world");
			activity.set(Activity.Phase.COPYING, worldName);
			copyWithRetry(worldDir, copy);
			Path zip = tmpDir.resolve("sync.zip");
			activity.set(Activity.Phase.COMPRESSING, worldName);
			BackupCompressor.zipDirectory(copy, zip, config.compressionLevel);
			FileUtil.deleteQuietly(copy);
			activity.set(Activity.Phase.VERIFYING, worldName);
			BackupVerifier.verifyZip(zip);
			HashUtil.Hashes h = HashUtil.hashFile(zip);

			long now = System.currentTimeMillis();
			String remoteName = SyncFileName.build(now, worldName, ws.syncId);
			Path send = zip; long expectSize = h.size(); String expectMd5 = h.md5();
			Map<String, String> props = new HashMap<>(Map.of("syncId", ws.syncId, "syncTime", Long.toString(now)));
			if (encrypting) {
				Path encFile = tmpDir.resolve("sync.zip.cvenc");
				cloud.encryption().encrypt(zip, encFile);
				HashUtil.Hashes eh = HashUtil.hashFile(encFile);
				send = encFile; remoteName += ".cvenc"; expectSize = eh.size(); expectMd5 = eh.md5(); props.put("encrypted", "1");
			} else props.put("sha256", h.sha256());

			activity.set(Activity.Phase.UPLOADING, worldName);
			RemoteFile rf = p.uploadSync(send, remoteName, SyncFileName.folder(worldName, ws.syncId), props);
			activity.set(Activity.Phase.VERIFYING_CLOUD, worldName);
			if (rf.size() != expectSize) throw new CloudException(CloudException.Kind.OTHER, "Cloud copy has wrong size");
			if (rf.md5() != null && !rf.md5().equalsIgnoreCase(expectMd5)) throw new CloudException(CloudException.Kind.OTHER, "Cloud copy checksum mismatch");

			ws.lastSyncRemoteId = rf.id(); ws.syncLocalFp = fp; ws.syncDirty = false; ws.lastSyncAt = now; ws.conflictRemoteId = null;
			saveConfig.run();
			status = "Last upload: " + worldName;
			activity.succeeded("Cloud sync of " + worldName);
			prune(p, worldId, ws, rf.id());
			return true;
		} catch (Throwable e) {
			String why = ErrorText.friendly(e);
			status = "Cloud sync of " + worldName + " failed: " + why;
			activity.failed("Cloud sync of " + worldName + ": " + why);
			CloudVault.LOGGER.warn("Cloud sync upload failed for {}: {}", worldName, ErrorText.redact(why));
			if (config.notifications) sink.notify(NotificationSink.Level.WARNING, "Cloud sync", worldName + ": " + why + ". It will be tried again next time Minecraft starts.");
			if (alerts != null) alerts.send(com.cloudvault.alerts.AlertManager.Event.SYNC_PROBLEM, "Cloud sync of " + worldName + " failed", why);
			return false; // syncDirty stays true
		} finally {
			FileUtil.deleteQuietly(tmpDir);
			activity.idle();
		}
	}

	private static void copyWithRetry(Path src, Path dst) throws IOException {
		IOException last = null;
		for (int i = 0; i < 3; i++) {
			try { FileUtil.copyTree(src, dst); return; }
			catch (IOException e) { last = e; FileUtil.deleteQuietly(dst); try { Thread.sleep(700); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; } }
		}
		throw last;
	}

	/** Keeps only the newest N sync copies of this world. Never deletes the one just uploaded. Failures are ignored (they only leave extra files). */
	private void prune(CloudProvider p, String worldId, WorldSettings ws, String justUploaded) {
		try {
			String id8 = SyncFileName.id8(ws.syncId);
			List<RemoteFile> mine = new ArrayList<>();
			for (RemoteFile f : p.listSync()) { var pn = SyncFileName.parse(f.name()); if (pn != null && pn.id8().equals(id8)) mine.add(p.details(f)); }
			mine.sort(NEWEST_FIRST);
			int keep = config.syncKeep(worldId);
			for (int i = keep; i < mine.size(); i++) if (!mine.get(i).id().equals(justUploaded)) p.deleteBackup(mine.get(i).id());
		} catch (Exception e) { CloudVault.LOGGER.warn("Could not remove old sync copies: {}", ErrorText.redact(String.valueOf(e.getMessage()))); }
	}

	private static String prop(RemoteFile f, String key) {
		for (var e : f.props().entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
		return null;
	}

	/** Upload time: the stored sync time when there is one (same on every computer), else the time in the file name. */
	private static long timeOf(RemoteFile f) {
		try { String t = prop(f, "syncTime"); if (t != null) { long v = Long.parseLong(t); if (v > 0) return v; } } catch (NumberFormatException ignored) { /* use the name */ }
		var pn = SyncFileName.parse(f.name());
		return pn == null ? 0 : pn.approxMillis();
	}

	private static final Comparator<RemoteFile> NEWEST_FIRST = Comparator.comparingLong(SyncManager::timeOf).reversed().thenComparing(RemoteFile::name, Comparator.reverseOrder());

	// ---------------------------------------------------------------- download

	/**
	 * Brings this computer up to date with the cloud sync folder. Blocking (network + disk): call from a background thread.
	 * @param isOpen true for a world folder that is open right now (it is left alone)
	 */
	public PullResult pullAll(Path savesDir, Predicate<Path> isOpen) {
		synchronized (pullLock) {
			int added = 0, updated = 0, copies = 0, skipped = 0;
			String error = null;
			try {
				CloudProvider p = cloud.provider();
				if (p == null || !p.isAuthenticated() || !config.cloudEnabled) { status = "Cloud sync: not connected to the cloud"; return new PullResult(0, 0, 0, 0, "Not connected to the cloud"); }
				activity.set(Activity.Phase.RESTORING, "cloud sync");
				Map<String, List<RemoteFile>> groups = new LinkedHashMap<>();
				for (RemoteFile f : p.listSync()) { var pn = SyncFileName.parse(f.name()); if (pn != null) groups.computeIfAbsent(pn.id8(), k -> new ArrayList<>()).add(f); }

				for (var g : groups.entrySet()) {
					String id8 = g.getKey();
					try {
						List<RemoteFile> fs = new ArrayList<>();
						for (RemoteFile f : g.getValue()) fs.add(p.details(f));
						fs.sort(NEWEST_FIRST);
						RemoteFile newest = fs.get(0);
						String folderLabel = newest.props().getOrDefault("cv_folder", "");
						String name = SyncFileName.worldOfFolder(folderLabel);
						if (name == null) name = SyncFileName.parse(newest.name()).worldName();
						String fullId = prop(newest, "syncId");
						if (fullId == null || !UUID_RE.matcher(fullId).matches() || !fullId.toLowerCase(Locale.ROOT).startsWith(id8)) fullId = id8 + "-0000-4000-8000-000000000000";
						String sha = prop(newest, "sha256");

						String localWorldId = null;
						for (var e : config.worlds.entrySet()) if (e.getValue().syncId != null && e.getValue().syncId.toLowerCase(Locale.ROOT).startsWith(id8)) { localWorldId = e.getKey(); break; }

						if (localWorldId == null) { // this computer has never had this world
							if (!config.sync.autoDownload) continue;
							Path zip = cloud.downloadPlainZip(p, newest.id(), newest.name(), sha);
							try {
								Path dir = restore.extractAsNewWorld(zip, name, savesDir);
								String wid = worlds.idFor(dir.getFileName().toString());
								WorldSettings ws = config.worldSettings(wid);
								ws.enabled = true; ws.syncEnabled = true; ws.syncId = fullId; ws.lastKnownName = name;
								ws.lastSyncRemoteId = newest.id(); ws.syncLocalFp = FileUtil.fingerprint(dir); ws.syncDirty = false; ws.lastSyncAt = System.currentTimeMillis();
								onWorldAdded.accept(fullId, ws);
								saveConfig.run();
								added++;
								if (config.notifications) sink.notify(NotificationSink.Level.SUCCESS, "Cloud sync", "Added world \"" + name + "\" from the cloud");
							} finally { FileUtil.deleteQuietly(zip); }
							continue;
						}

						WorldSettings ws = config.worldSettings(localWorldId);
						String folder = worlds.folderFor(localWorldId);
						Path dir = folder == null ? null : savesDir.resolve(folder);
						if (dir == null || !Files.isRegularFile(dir.resolve("level.dat"))) continue; // removed on this computer on purpose: not brought back
						if (!config.isWorldSynced(localWorldId)) continue;                           // sync switched off for it here
						if (newest.id().equals(ws.lastSyncRemoteId) || newest.id().equals(ws.conflictRemoteId)) continue; // already have / already handled
						if (isOpen.test(dir)) { skipped++; continue; }

						boolean localUnchanged = !ws.syncDirty && ws.syncLocalFp != null && ws.syncLocalFp.equals(FileUtil.fingerprint(dir));
						Path zip = cloud.downloadPlainZip(p, newest.id(), newest.name(), sha);
						try {
							if (localUnchanged) {
								restore.replaceFromZip(zip, dir); // old folder is kept in cloudvault/replaced
								ws.lastSyncRemoteId = newest.id(); ws.syncLocalFp = FileUtil.fingerprint(dir); ws.lastSyncAt = System.currentTimeMillis(); ws.conflictRemoteId = null;
								saveConfig.run();
								updated++;
								if (config.notifications) sink.notify(NotificationSink.Level.SUCCESS, "Cloud sync", "Updated \"" + name + "\" from the cloud");
							} else { // changed here AND in the cloud: never overwrite, add the cloud version next to it
								Path copyDir = restore.extractAsNewWorld(zip, name + " (cloud copy)", savesDir);
								String cid = worlds.idFor(copyDir.getFileName().toString());
								WorldSettings cw = config.worldSettings(cid);
								cw.enabled = true; cw.syncEnabled = false; cw.lastKnownName = name + " (cloud copy)";
								ws.conflictRemoteId = newest.id();
								saveConfig.run();
								copies++;
								if (alerts != null) alerts.send(com.cloudvault.alerts.AlertManager.Event.SYNC_PROBLEM, "World changed on two computers", "\"" + name + "\" was changed on two computers. The cloud version was added next to it as a copy.");
								if (config.notifications) sink.notify(NotificationSink.Level.WARNING, "Cloud sync", "\"" + name + "\" changed on two computers. The cloud version was added as a copy.");
							}
						} finally { FileUtil.deleteQuietly(zip); }
					} catch (CloudException e) {
						if (e.kind == CloudException.Kind.AUTH || e.kind == CloudException.Kind.NETWORK) throw e;
						skipped++; CloudVault.LOGGER.warn("Cloud sync skipped a world: {}", ErrorText.redact(String.valueOf(e.getMessage())));
					} catch (IOException | RuntimeException e) {
						skipped++; CloudVault.LOGGER.warn("Cloud sync skipped a world: {}", ErrorText.redact(String.valueOf(e.getMessage())));
					}
				}

				// uploads that did not finish last time (offline, game closed): try again now
				for (var e : new ArrayList<>(config.worlds.entrySet())) {
					WorldSettings ws = e.getValue();
					if (!ws.syncDirty || !config.isWorldSynced(e.getKey())) continue;
					String folder = worlds.folderFor(e.getKey());
					Path dir = folder == null ? null : savesDir.resolve(folder);
					if (dir == null || !Files.isRegularFile(dir.resolve("level.dat")) || isOpen.test(dir)) continue;
					requestPush(e.getKey(), ws.lastKnownName.isEmpty() ? folder : ws.lastKnownName, dir);
				}
			} catch (Exception e) {
				error = ErrorText.friendly(e);
			} finally { activity.idle(); }
			status = error != null ? "Cloud sync: " + error : "Last check: " + new PullResult(added, updated, copies, skipped, null).summary();
			return new PullResult(added, updated, copies, skipped, error);
		}
	}
}
