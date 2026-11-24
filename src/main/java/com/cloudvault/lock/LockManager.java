package com.cloudvault.lock;

import com.cloudvault.CloudVault;
import com.cloudvault.alerts.AlertManager;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.sync.SyncFileName;
import com.cloudvault.util.DeviceInfo;
import com.cloudvault.util.ErrorText;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * World-in-use lock for cloud-synced worlds. While a world is open on one computer (and until its leave-upload has finished) a small lock
 * file in the world's cloud sync folder says so. Another computer that opens the same world gets a warning first. A lock that is not
 * renewed for a while counts as stale (the other computer probably crashed). If the cloud cannot be reached the lock never blocks anything.
 */
public class LockManager {
	public enum State { FREE, HELD_BY_OTHER, STALE_OTHER, UNKNOWN }
	public record Check(State state, String device, long startedAt, long lastSeen) {}

	static final class Lock { String deviceId, deviceName, world, token; long startedAt, heartbeat; }

	private static final Gson GSON = new Gson();
	private static final int HEARTBEAT_SECONDS = 120;
	private final CloudVaultConfig config;
	private final CloudManager cloud;
	private final BackupStore store;
	private final NotificationSink sink;
	private volatile AlertManager alerts;
	private volatile String mcVersion = "";
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CloudVault-Lock"); t.setDaemon(true); return t; });
	private ScheduledFuture<?> beat;
	private Lock mine; private String folder, id8, mineRemoteId, worldName;

	public LockManager(CloudVaultConfig config, CloudManager cloud, BackupStore store, NotificationSink sink) { this.config = config; this.cloud = cloud; this.store = store; this.sink = sink; }

	public void setAlerts(AlertManager a) { this.alerts = a; }
	public void setMcVersion(String v) { mcVersion = v == null ? "" : v; }
	public synchronized boolean holding() { return mine != null; }

	private CloudProvider provider() { CloudProvider p = cloud.provider(); return p != null && p.isAuthenticated() && config.cloudEnabled ? p : null; }

	private Lock read(CloudProvider p, CloudProvider.RemoteFile f) {
		Path tmp = store.temp.resolve("lock-" + UUID.randomUUID() + ".json");
		try { p.downloadBackup(f.id(), tmp); return GSON.fromJson(Files.readString(tmp), Lock.class); }
		catch (Exception e) { return null; }
		finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp */ } }
	}

	/** Looks at the lock files of one world. Blocking (network). */
	public Check check(String worldName, String syncId) {
		CloudProvider p = provider();
		if (p == null || !config.lock.enabled) return new Check(State.UNKNOWN, "", 0, 0);
		try {
			String folder = SyncFileName.folder(worldName, syncId);
			Lock newest = null;
			for (CloudProvider.RemoteFile f : p.listFiles("sync", folder, "lock-")) {
				Lock l = read(p, f);
				if (l != null && l.deviceId != null && (newest == null || l.heartbeat > newest.heartbeat)) newest = l;
			}
			if (newest == null || newest.deviceId.equals(config.account.deviceId)) return new Check(State.FREE, "", 0, 0);
			long age = System.currentTimeMillis() - newest.heartbeat;
			State st = age <= config.lock.staleMinutes * 60_000L ? State.HELD_BY_OTHER : State.STALE_OTHER;
			return new Check(st, newest.deviceName == null ? "another computer" : newest.deviceName, newest.startedAt, newest.heartbeat);
		} catch (Exception e) {
			CloudVault.LOGGER.warn("World lock check failed: {}", ErrorText.redact(String.valueOf(e.getMessage())));
			return new Check(State.UNKNOWN, "", 0, 0);
		}
	}

	/** Takes the lock (also used to "play anyway") and keeps renewing it. Blocking for the first write. */
	public synchronized void acquire(String worldName, String syncId) {
		release(true);
		CloudProvider p = provider();
		if (p == null || !config.lock.enabled) return;
		this.worldName = worldName; this.folder = SyncFileName.folder(worldName, syncId); this.id8 = SyncFileName.id8(syncId);
		Lock l = new Lock(); l.deviceId = config.account.deviceId; l.deviceName = DeviceInfo.describe(config.account.deviceName, mcVersion); l.world = worldName; l.token = UUID.randomUUID().toString(); l.startedAt = System.currentTimeMillis();
		mine = l;
		write(p);
		beat = timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
	}

	private void write(CloudProvider p) {
		Path tmp = store.temp.resolve("lock-" + UUID.randomUUID() + ".json");
		try {
			mine.heartbeat = System.currentTimeMillis();
			Files.writeString(tmp, GSON.toJson(mine));
			String old = mineRemoteId;
			CloudProvider.RemoteFile rf = p.putFile("sync", folder, "lock-" + id8 + "-" + System.currentTimeMillis() + ".json", tmp, java.util.Map.of("lock", "1"));
			mineRemoteId = rf.id();
			if (old != null) try { p.deleteBackup(old); } catch (Exception ignored) { /* a stale extra lock file is harmless: the newest one wins */ }
		} catch (Exception e) { CloudVault.LOGGER.warn("Could not write the world lock: {}", ErrorText.redact(String.valueOf(e.getMessage()))); }
		finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp */ } }
	}

	private synchronized void heartbeat() {
		CloudProvider p = provider();
		if (p == null || mine == null) return;
		try {
			for (CloudProvider.RemoteFile f : p.listFiles("sync", folder, "lock-")) { // did another computer take over?
				if (f.id().equals(mineRemoteId)) continue;
				Lock l = read(p, f);
				if (l != null && l.deviceId != null && !l.deviceId.equals(config.account.deviceId) && l.heartbeat > mine.heartbeat) {
					String who = l.deviceName == null ? "another computer" : l.deviceName;
					if (config.notifications) sink.notify(NotificationSink.Level.WARNING, "World in use", "\"" + worldName + "\" was opened on " + who + ". Changes made on two computers cannot be merged.");
					if (alerts != null) alerts.send(AlertManager.Event.WORLD_TAKEOVER, "World opened elsewhere", "\"" + worldName + "\" was opened on " + who + " while it was open here.");
					if (beat != null) beat.cancel(false);
					return;
				}
			}
		} catch (Exception ignored) { /* renew anyway */ }
		write(p);
	}

	/**
	 * Stops renewing. {@code delete} = remove the lock (the leave-upload finished). When the upload failed the lock is left to go stale,
	 * which tells the other computer "this one may not have finished uploading".
	 */
	public synchronized void release(boolean delete) {
		if (beat != null) { beat.cancel(false); beat = null; }
		if (mine == null) return;
		CloudProvider p = provider();
		if (delete && p != null && mineRemoteId != null) try { p.deleteBackup(mineRemoteId); } catch (Exception ignored) { /* goes stale by itself */ }
		mine = null; mineRemoteId = null;
	}

	public void shutdown() { timer.shutdownNow(); }
}
