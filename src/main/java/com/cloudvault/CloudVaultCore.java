package com.cloudvault;

import com.cloudvault.backup.*;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudImporter;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.cloud.StorageManager;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.config.WorldSettings;
import com.cloudvault.crypto.EncryptionService;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.restore.RestoreManager;
import com.cloudvault.sync.SyncManager;
import com.cloudvault.scheduler.GameplayScheduler;
import com.cloudvault.scheduler.PeriodicScheduler;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.WorldUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Minecraft-independent brain. The client module calls these methods; the game thread must flush/save the
 * world BEFORE calling requestBackup().
 */
public class CloudVaultCore {
	public final Path root;
	public final Path configFile;
	public final CloudVaultConfig config;
	public final BackupStore store;
	public final BackupQueue queue = new BackupQueue();
	public final RetentionManager retention;
	public final CloudManager cloud;
	public final EncryptionService encryption;
	public final StorageManager storage;
	public final CloudImporter importer;
	public final com.cloudvault.backup.Activity activity = new com.cloudvault.backup.Activity();
	public final BackupManager backups;
	public final RestoreManager restore;
	public final com.cloudvault.restore.RestoreTester restoreTester;
	public final SyncManager sync;
	public final com.cloudvault.util.SecretStore secrets;
	public final com.cloudvault.alerts.AlertManager alerts;
	public final com.cloudvault.mirror.MirrorManager mirror;
	public final com.cloudvault.lock.LockManager lock;
	public final com.cloudvault.smart.SmartTriggers smart = new com.cloudvault.smart.SmartTriggers();
	public final com.cloudvault.account.AccountManager account;
	public final com.cloudvault.share.ShareManager shares;
	private final NotificationSink sink;
	public final WorldUtil worlds;
	public final PeriodicScheduler periodic = new PeriodicScheduler();
	public final GameplayScheduler gameplay = new GameplayScheduler();

	private volatile Path currentWorldDir;
	private volatile String currentWorldName, currentWorldId, mcVersion = "unknown";

	public CloudVaultCore(Path root, NotificationSink sink) throws IOException {
		sink = new com.cloudvault.notification.DedupeSink(sink, 15 * 60_000L); // the same warning/error is shown at most once per 15 minutes
		this.root = root;
		this.configFile = root.resolve("config.json");
		this.config = CloudVaultConfig.load(configFile);
		this.store = new BackupStore(root);
		store.cleanTemp();
		FileUtil.sweepTmp(root); FileUtil.sweepTmp(store.metadata); FileUtil.sweepTmp(store.auth);
		this.worlds = new WorldUtil(root);
		this.retention = new RetentionManager(store);
		this.encryption = new EncryptionService(store.auth);
		this.cloud = new CloudManager(store, config, queue, retention, sink, encryption);
		this.storage = new StorageManager(store, cloud);
		this.importer = new CloudImporter(store, cloud, worlds::idStartingWith);
		this.backups = new BackupManager(store, config, cloud, retention, queue, sink);
		this.restore = new RestoreManager(store, cloud, backups);
		this.sink = sink;
		this.restoreTester = new com.cloudvault.restore.RestoreTester(store, cloud);
		this.sync = new SyncManager(store, config, cloud, worlds, restore, queue, sink, this::saveConfig);
		sync.setActivity(activity);
		this.secrets = new com.cloudvault.util.SecretStore(store.auth);
		this.alerts = new com.cloudvault.alerts.AlertManager(config, secrets);
		this.mirror = new com.cloudvault.mirror.MirrorManager(store, config, cloud, queue, encryption, sink);
		this.lock = new com.cloudvault.lock.LockManager(config, cloud, store, sink);
		this.account = new com.cloudvault.account.AccountManager(config, cloud, secrets, store, sink, this::saveConfig);
		this.shares = new com.cloudvault.share.ShareManager(store, cloud, root);
		backups.setMirror(mirror); backups.setAlerts(alerts); cloud.setMirror(mirror); cloud.setAlerts(alerts); mirror.setAlerts(alerts);
		sync.setLock(lock); sync.setAlerts(alerts); lock.setAlerts(alerts);
		sync.setOnWorldAdded(account::applyWorld);
		retention.onDeleted = mirror::deleteCopy;
		cloud.setPlaying(this::inWorld);
		com.cloudvault.cloud.Throttle.setLimitKBps(config.upload.speedLimitKBps);
		mirror.setProvider(com.cloudvault.mirror.MirrorManager.build(config, secrets));
		cloud.setActivity(activity); backups.setActivity(activity); restore.setActivity(activity); restoreTester.setActivity(activity);
	}

	// ---- comments: the client shows a window and calls back (null = skip this backup, "" = back up without a comment) ----
	public interface CommentPrompt { void ask(BackupType type, String worldName, java.util.function.Consumer<String> done); }
	public volatile CommentPrompt commentPrompt;

	/** True when this backup type asks for a comment in the current world (its own settings if it has them). */
	public boolean commentWanted(BackupType type) { return commentPrompt != null && currentWorldId != null && config.effective(currentWorldId).commentFor(type); }

	/** Submits a backup of an open world, asking for a comment first when that is switched on for this type. */
	private void submitMaybeAsk(BackupManager.Request req) {
		CommentPrompt cp = commentPrompt;
		if (cp != null && config.effective(req.worldId()).commentFor(req.type()))
			cp.ask(req.type(), req.worldName(), c -> { if (c != null) backups.submit(req.withComment(c), null); });
		else backups.submit(req, null);
	}

	/** Adds a world from a cloud backup to the saves folder as a new world. Records nothing else (no backup list entry, no cache). Blocking. */
	public Path importWorldFromCloud(CloudProvider.RemoteFile file, String folderName, Path savesDir) throws IOException {
		CloudProvider p = cloud.provider();
		if (p == null || !p.isAuthenticated()) throw new com.cloudvault.cloud.CloudException(com.cloudvault.cloud.CloudException.Kind.AUTH, "Not connected to the cloud");
		activity.set(Activity.Phase.RESTORING, folderName);
		Path zip = null;
		try {
			CloudProvider.RemoteFile d = p.details(file);
			String sha = null;
			for (var e : d.props().entrySet()) if (e.getKey().equalsIgnoreCase("sha256")) sha = e.getValue();
			zip = cloud.downloadPlainZip(p, file.id(), file.name(), sha);
			return restore.extractAsNewWorld(zip, folderName, savesDir);
		} finally {
			if (zip != null) FileUtil.deleteQuietly(zip);
			activity.idle();
		}
	}

	// ---- pause automatic backups (manual and safety backups are never paused) ----
	public boolean isAutoPaused() {
		long u = config.autoPausedUntil;
		if (u == 0) return false;
		if (u != Long.MAX_VALUE && System.currentTimeMillis() >= u) { config.autoPausedUntil = 0; saveConfig(); return false; }
		return true;
	}
	/** @param minutes pause length; 0 or less = until resumed */
	public void pauseAuto(long minutes) { config.autoPausedUntil = minutes <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L; saveConfig(); }
	public void resumeAuto() { config.autoPausedUntil = 0; saveConfig(); }
	/** Minutes left of a timed pause; -1 = until resumed; 0 = not paused. */
	public long pausedMinutesLeft() {
		if (!isAutoPaused()) return 0;
		return config.autoPausedUntil == Long.MAX_VALUE ? -1 : Math.max(1, (config.autoPausedUntil - System.currentTimeMillis() + 59_999) / 60_000);
	}

	// ---- per-world on/off (the "CloudVault enabled" checkbox) ----
	public boolean isWorldDisabled(String worldId) { return config.isWorldDisabled(worldId); }

	/** Switches CloudVault on or off for one world (any world, loaded or not). Switching on lets waiting uploads continue. */
	public void setWorldEnabled(String worldId, String worldName, boolean enabled) {
		WorldSettings w = config.worldSettings(worldId);
		w.enabled = enabled;
		if (worldName != null && !worldName.isBlank()) w.lastKnownName = worldName;
		saveConfig();
		if (enabled) {
			cloud.resumePending();
			if (worldId.equals(currentWorldId)) periodic.reschedule(settings().periodic, ZonedDateTime.now());
		}
	}

	/**
	 * The "new worlds start with CloudVault" default. Turning it OFF must not silently switch off worlds that are already being
	 * backed up, so every world that has backups and no explicit choice is first marked ON.
	 */
	public void setNewWorldsEnabled(boolean on) {
		if (!on) {
			for (BackupMetadata m : store.all()) {
				WorldSettings w = config.worldSettings(m.worldId);
				if (w.enabled == null && !w.disabled) { w.enabled = true; if (w.lastKnownName.isEmpty()) w.lastKnownName = m.worldName; }
			}
		}
		config.newWorldsEnabled = on;
		saveConfig();
		if (on) cloud.resumePending();
	}

	// ---- pins and notes ----
	public void setPinned(String backupId, boolean pinned) throws IOException { store.update(backupId, m -> m.pinned = pinned); }

	public static String cleanNote(String text) {
		String t = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").trim();
		return t.length() > 80 ? t.substring(0, 80) : t;
	}

	public void setNote(String backupId, String text) throws IOException {
		String n = cleanNote(text);
		store.update(backupId, m -> m.note = n.isEmpty() ? null : n);
	}

	// ---- "before update" safety backup ----
	/**
	 * Called as a world is about to open. Returns why a safety backup is needed (Minecraft or mods changed since this world was last
	 * seen), or null. Records what it saw, so each change triggers once.
	 */
	public synchronized String checkUpdate(String worldId, String levelVersion, String currentMc, String modsHash) {
		WorldSettings w = config.worldSettings(worldId);
		String reason = com.cloudvault.backup.UpdateCheck.reason(levelVersion, currentMc, w.lastMcVersion, w.lastModsHash, modsHash);
		w.lastMcVersion = currentMc; w.lastModsHash = modsHash;
		saveConfig();
		return reason;
	}

	// ---- restore tests ----
	private long lastTestPoll = 0;
	private final java.util.Map<String, Long> testAttempt = new java.util.concurrent.ConcurrentHashMap<>();

	/** Cheap; call about once a second while playing. Starts a quiet restore test when one is due (never more than one per world per 6 hours). */
	public void maybeRestoreTest() {
		long now = System.currentTimeMillis();
		if (now - lastTestPoll < 60_000) return;
		lastTestPoll = now;
		String id = currentWorldId;
		if (id == null || !config.enabled || config.restoreTestDays <= 0 || isWorldDisabled(id)) return;
		if (activity.phase() != com.cloudvault.backup.Activity.Phase.IDLE || queue.size() > 0) return;
		Long att = testAttempt.get(id);
		if (att != null && now - att < 6 * 3_600_000L) return;
		BackupMetadata latest = restore.latestUsable(id);
		if (latest == null) return;
		long newestTest = store.forWorld(id).stream().mapToLong(m -> m.testedAt).max().orElse(0);
		if (newestTest != 0 && now - newestTest < config.restoreTestDays * 86_400_000L) return;
		testAttempt.put(id, now);
		queue.submit(9, "restoretest:" + id, () -> runTest(latest, false));
	}

	/** Blocking. Tests one backup and reports problems (a failed test is an error notification; success stays quiet). */
	public com.cloudvault.restore.RestoreTester.Result runTest(BackupMetadata m, boolean allowDownload) {
		var r = restoreTester.test(m, allowDownload);
		if (r.outcome() == com.cloudvault.restore.RestoreTester.Outcome.OK) activity.succeeded("Restore test of " + m.worldName + ": OK");
		else if (r.outcome() == com.cloudvault.restore.RestoreTester.Outcome.FAILED) {
			activity.failed("Restore test of " + m.worldName + ": " + r.message());
			if (config.notifications) sink.notify(NotificationSink.Level.ERROR, "Restore test failed", m.worldName + ": " + r.message() + ". Make a new backup.");
			alerts.send(com.cloudvault.alerts.AlertManager.Event.RESTORE_TEST_FAILED, "Restore test failed", m.worldName + ": " + r.message());
		}
		return r;
	}

	// ---- space ----
	/** A short warning for the status screen when this computer or the cloud is nearly full, else null. */
	public String spaceWarning() {
		long free = backups.freeBytes();
		if (free >= 0 && free < BackupManager.LOW_DISK_BYTES) return "Low disk space on this computer (" + StorageManager.human(free) + " free)";
		int pc = cloud.quotaPercent();
		return pc >= 90 ? "Cloud storage is " + pc + "% full" : null;
	}

	public void setProvider(CloudProvider p) { cloud.setProvider(p); cloud.resumePending(); }
	/** A setting was changed in the screens: saved now, applied now, and the settings file in the cloud is refreshed a few seconds later. */
	public void settingsChanged() {
		saveConfig();
		com.cloudvault.cloud.Throttle.setLimitKBps(config.upload.speedLimitKBps);
		mirror.setProviderIfLocal(config, secrets);
		account.markDirty();
	}

	/** Called at game start (background): second-destination copies that are missing, expired shares, and uploads that were waiting. */
	public void startupTasks() {
		try { mirror.backfill(); } catch (RuntimeException e) { CloudVault.LOGGER.warn("Second destination check failed: {}", e.getMessage()); }
		try { shares.cleanExpired(); } catch (RuntimeException ignored) { /* later */ }
		cloud.kickWaiting();
	}

	// ---- world in use (cloud-synced worlds only) ----
	/** The client shows the warning: playAnyway takes the lock over. */
	public interface LockPrompt { void conflict(String worldName, com.cloudvault.lock.LockManager.Check check, Runnable playAnyway); }
	public volatile LockPrompt lockPrompt;
	private final java.util.Set<String> noPushOnLeave = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The player chose to leave a world another computer has open: nothing of it is uploaded when it closes (it was not played). */
	public void skipSyncOnLeave() { if (currentWorldId != null) noPushOnLeave.add(currentWorldId); }

	private void checkWorldLock(String worldId, String worldName) {
		if (!config.isWorldSynced(worldId) || !config.lock.enabled) return;
		WorldSettings ws = config.worldSettings(worldId);
		if (ws.syncId == null) { ws.syncId = java.util.UUID.randomUUID().toString(); saveConfig(); }
		final String syncId = ws.syncId;
		Thread t = new Thread(() -> {
			var chk = lock.check(worldName, syncId);
			if (chk.state() == com.cloudvault.lock.LockManager.State.HELD_BY_OTHER || chk.state() == com.cloudvault.lock.LockManager.State.STALE_OTHER) {
				LockPrompt lp = lockPrompt;
				if (lp != null) { lp.conflict(worldName, chk, () -> lock.acquire(worldName, syncId)); return; }
			}
			lock.acquire(worldName, syncId);
		}, "CloudVault-WorldLock");
		t.setDaemon(true); t.start();
	}

	// ---- smart triggers ----
	/** Called about once a second with what the game sees. Returns why a backup should be made now (the caller makes it). */
	public java.util.Optional<String> smartCheck(com.cloudvault.smart.SmartTriggers.Observation o) {
		if (currentWorldDir == null || !config.enabled || isAutoPaused() || isWorldDisabled(currentWorldId)) return java.util.Optional.empty();
		return smart.observe(settings(), o, System.currentTimeMillis());
	}

	private volatile long lastWatchdog = 0;

	/** While playing: tell the player (outside the game) when no backup has worked for the set number of days. */
	public void watchdog() {
		long now = System.currentTimeMillis();
		if (!config.alerts.enabled || config.alerts.noBackupDays <= 0 || now - lastWatchdog < 3_600_000L || currentWorldDir == null || isWorldDisabled(currentWorldId)) return;
		lastWatchdog = now;
		long newest = 0;
		for (BackupMetadata m : store.all()) if (m.uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED || m.localVerified) newest = Math.max(newest, m.timestamp);
		long days = (now - newest) / 86_400_000L;
		if (newest == 0 || days >= config.alerts.noBackupDays) alerts.send(com.cloudvault.alerts.AlertManager.Event.NO_RECENT_BACKUP, "No recent backup", newest == 0 ? "No backup has been made yet." : "The last successful backup is " + days + " days old.");
	}

	public synchronized void saveConfig() { try { config.save(configFile); } catch (IOException e) { CloudVault.LOGGER.error("Could not save config: {}", e.getMessage()); } }
	public boolean inWorld() { return currentWorldDir != null; }
	public String currentWorldName() { return currentWorldName; }
	public String currentWorldId() { return currentWorldId; }
	public Path currentWorldDir() { return currentWorldDir; }
	public BackupSettings settings() { return currentWorldId == null ? config.global : config.effective(currentWorldId); }

	/** World finished loading. Call flush-free; join backup is requested by the caller after init is done. */
	public void onWorldJoined(Path worldDir, String folderName, String displayName, String mcVersion) {
		this.currentWorldDir = worldDir; this.currentWorldName = displayName; this.mcVersion = mcVersion;
		this.currentWorldId = worlds.idFor(folderName);
		config.worldSettings(currentWorldId).lastKnownName = displayName;
		BackupSettings s = settings();
		gameplay.start();
		ZonedDateTime last = store.forWorld(currentWorldId).stream().filter(m -> m.backupType == BackupType.PERIODIC).findFirst()
			.map(m -> Instant.ofEpochMilli(m.timestamp).atZone(ZoneId.systemDefault())).orElse(null);
		periodic.start(last, s.periodic, ZonedDateTime.now());
		smart.reset(System.currentTimeMillis());
		alerts.setMcVersion(mcVersion); lock.setMcVersion(mcVersion); account.setMcVersion(mcVersion);
		if (config.enabled && s.session.joinEnabled && !isAutoPaused() && !isWorldDisabled(currentWorldId)) submitMaybeAsk(currentWorldRequestOf(BackupType.SESSION_JOIN));
		checkWorldLock(currentWorldId, displayName);
	}

	/** World is leaving (caller already saved it). */
	public void onWorldLeaving() {
		if (currentWorldDir == null) return;
		Path dir = currentWorldDir; String id = currentWorldId, name = currentWorldName;
		boolean leaveBackup = config.enabled && settings().session.leaveEnabled && !isAutoPaused() && !isWorldDisabled(id);
		BackupManager.Request req = currentWorldRequestOf(BackupType.SESSION_LEAVE);
		gameplay.stop(); periodic.stop();
		currentWorldDir = null; currentWorldId = null; currentWorldName = null;
		if (leaveBackup) submitMaybeAsk(req);   // asks for a comment first when switched on (the window appears on the title screen)
		boolean skipPush = noPushOnLeave.remove(id);
		if (!skipPush) sync.requestPush(id, name, dir); // cloud sync: only when sync is on for this world (the world lock is released when it finished)
		if (skipPush || !config.isWorldSynced(id)) lock.release(!skipPush);
		cloud.kickWaiting();                    // uploads that waited for you to stop playing
	}

	/** Call about once per second from the client tick. Cheap. Caller must flush the world before calling when it returns true types. */
	public BackupType pollScheduled() {
		if (currentWorldDir == null || !config.enabled || isAutoPaused() || isWorldDisabled(currentWorldId)) return null;
		BackupSettings s = settings();
		if (gameplay.tick(s.gameplay)) return BackupType.GAMEPLAY;
		if (periodic.tick(s.periodic, ZonedDateTime.now())) return BackupType.PERIODIC;
		return null;
	}

	public void request(BackupType type) { request(type, null); }

	/** @param comment text for the backup (null = none) */
	public void request(BackupType type, String comment) {
		if (currentWorldDir == null) return;
		backups.submit(currentWorldRequestOf(type).withComment(comment), null);
	}

	private BackupManager.Request currentWorldRequestOf(BackupType type) {
		return new BackupManager.Request(currentWorldDir, currentWorldName, currentWorldId, type, mcVersion);
	}

	/** Safety-backup request describing the current world (for restore). Null if no world is open. */
	public BackupManager.Request currentWorldRequest() {
		return currentWorldDir == null ? null : new BackupManager.Request(currentWorldDir, currentWorldName, currentWorldId, BackupType.SAFETY, mcVersion);
	}

	public void shutdown() { saveConfig(); lock.shutdown(); mirror.shutdown(); account.shutdown(); alerts.shutdown(); cloud.shutdown(); queue.shutdown(); }
}
