package com.cloudvault.config;

import com.cloudvault.util.FileUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persisted settings (cloudvault/config.json). No secrets are stored here; tokens live in auth/. */
public class CloudVaultConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean enabled = true;
	/** Worlds without an explicit on/off choice follow this. Turning it off makes CloudVault OFF by default for worlds you have not switched on. */
	public boolean newWorldsEnabled = true;
	/** Small "CloudVault: ON/OFF" indicator while playing, on the pause menu button and a note when a world opens. */
	public boolean showWorldIndicator = true;
	/** Make a safety backup before opening a world after Minecraft or the mods changed. */
	public boolean backupBeforeUpdate = true;
	/** Quietly test that the latest backup can be restored every N days per world (0 = never). */
	public int restoreTestDays = 7;
	public boolean firstLaunchDone = false;
	public boolean notifications = true;
	public String cloudProvider = "google_drive";
	public boolean cloudEnabled = true;
	public boolean retryFailedUploads = true;
	public int retryDelaySeconds = 60;
	public int compressionLevel = 6; // 0..9 (Deflater); see CompressionPreset
	/** 0 = automatic backups running; otherwise epoch millis until which they are paused (Long.MAX_VALUE = until resumed). Manual backups are never paused. */
	public long autoPausedUntil = 0;
	/** EXPERIMENTAL: encrypt backups on this computer before uploading. Needs a key set up in EncryptionService. */
	public boolean encryptionEnabled = false;
	/** S3-compatible storage (AWS S3, Cloudflare R2, Backblaze B2, MinIO...). The secret key is NOT here; it is kept in auth/. */
	public String s3Endpoint = "", s3Region = "auto", s3Bucket = "", s3AccessKey = "";
	public boolean s3PathStyle = true;
	public int localCacheKeep = 3;   // 0 = disabled, else keep last N zips locally
	/** Cloud sync defaults (per-world choices live in WorldSettings). */
	public SyncSettings sync = new SyncSettings();
	public UploadSettings upload = new UploadSettings();
	public AlertSettings alerts = new AlertSettings();
	public MirrorSettings mirror = new MirrorSettings();
	public LockSettings lock = new LockSettings();
	public AccountSettings account = new AccountSettings();
	public BackupSettings global = new BackupSettings();
	public Map<String, WorldSettings> worlds = new HashMap<>(); // key = worldId

	public BackupSettings effective(String worldId) {
		WorldSettings w = worlds.get(worldId);
		return (w != null && w.useOverride) ? w.settings : global;
	}

	/** True when CloudVault must do nothing for this world: its own choice, else the "new worlds" default. */
	public boolean isWorldDisabled(String worldId) {
		if (worldId == null) return false; // not in a world: nothing to disable
		WorldSettings w = worlds.get(worldId);
		if (w != null) {
			if (w.enabled != null) return !w.enabled;
			if (w.disabled) return true;
		}
		return !newWorldsEnabled;
	}

	/** Cloud sync is on for this world: its own choice, else the "new worlds" default. Never for a world CloudVault is switched off for. */
	public boolean isWorldSynced(String worldId) {
		if (worldId == null || isWorldDisabled(worldId)) return false;
		WorldSettings w = worlds.get(worldId);
		return w != null && w.syncEnabled != null ? w.syncEnabled : sync.newWorldsSync;
	}

	/** Sync copies kept in the cloud for this world (its own number, else the global one). */
	public int syncKeep(String worldId) {
		WorldSettings w = worlds.get(worldId);
		return SyncSettings.clampKeep(w != null && w.syncKeep != null ? w.syncKeep : sync.keep);
	}

	public WorldSettings worldSettings(String worldId) {
		return worlds.computeIfAbsent(worldId, k -> new WorldSettings());
	}

	public void sanitize() {
		retryDelaySeconds = Math.max(10, Math.min(86_400, retryDelaySeconds));
		compressionLevel = Math.max(0, Math.min(9, compressionLevel));
		localCacheKeep = Math.max(0, Math.min(10, localCacheKeep));
		restoreTestDays = Math.max(0, Math.min(365, restoreTestDays));
		if (upload == null) upload = new UploadSettings();
		if (alerts == null) alerts = new AlertSettings();
		if (mirror == null) mirror = new MirrorSettings();
		if (lock == null) lock = new LockSettings();
		if (account == null) account = new AccountSettings();
		upload.sanitize(); alerts.sanitize(); mirror.sanitize(); lock.sanitize();
		if (account.deviceId == null || account.deviceId.isBlank()) account.deviceId = java.util.UUID.randomUUID().toString();
		if (account.deviceName == null) account.deviceName = "";
		if (sync == null) sync = new SyncSettings();
		sync.keep = SyncSettings.clampKeep(sync.keep);
		if (global == null) global = new BackupSettings();
		if (worlds == null) worlds = new HashMap<>();
		worlds.values().removeIf(java.util.Objects::isNull);
		fix(global);
		for (WorldSettings w : worlds.values()) {
			if (w.settings == null) w.settings = new BackupSettings();
			if (w.lastKnownName == null) w.lastKnownName = "";
			if (w.syncKeep != null) w.syncKeep = SyncSettings.clampKeep(w.syncKeep);
			if (w.disabled && w.enabled == null) w.enabled = false; // convert the old format
			w.disabled = false;
			fix(w.settings);
		}
	}

	private static void fix(BackupSettings b) {
		b.fixNew();
		if (b.session == null) b.session = new BackupSettings.Session();
		if (b.periodic == null) b.periodic = new BackupSettings.Periodic();
		if (b.gameplay == null) b.gameplay = new BackupSettings.Gameplay();
		if (b.manual == null) b.manual = new BackupSettings.Manual();
		if (b.periodic.unit == null) b.periodic.unit = com.cloudvault.util.TimeUtil.Unit.HOURS;
		if (b.gameplay.unit == null) b.gameplay.unit = com.cloudvault.util.TimeUtil.Unit.HOURS;
		if (b.advanced == null) b.advanced = new RetentionPolicy();
		b.advanced.sanitize();
		b.session.retention = BackupSettings.clampRetention(b.session.retention);
		b.periodic.retention = BackupSettings.clampRetention(b.periodic.retention);
		b.gameplay.retention = BackupSettings.clampRetention(b.gameplay.retention);
		b.manual.retention = BackupSettings.clampRetention(b.manual.retention);
		if (!com.cloudvault.util.TimeUtil.validAmount(b.periodic.amount, b.periodic.unit)) { b.periodic.amount = 2; b.periodic.unit = com.cloudvault.util.TimeUtil.Unit.HOURS; }
		if (b.gameplay.unit.isCalendar() || !com.cloudvault.util.TimeUtil.validAmount(b.gameplay.amount, b.gameplay.unit)) { b.gameplay.amount = 2; b.gameplay.unit = com.cloudvault.util.TimeUtil.Unit.HOURS; }
	}

	public static CloudVaultConfig load(Path file) {
		try {
			if (Files.exists(file)) {
				CloudVaultConfig c = GSON.fromJson(Files.readString(file), CloudVaultConfig.class);
				if (c != null) { c.sanitize(); return c; }
			}
		} catch (Exception e) {
			// Corrupt config: keep a copy, start from defaults rather than crash.
			try { Files.move(file, file.resolveSibling("config.json.corrupt-" + System.currentTimeMillis())); } catch (IOException ignored) {}
		}
		return new CloudVaultConfig();
	}

	public synchronized void save(Path file) throws IOException {
		sanitize();
		FileUtil.writeAtomic(file, GSON.toJson(this));
	}
}
