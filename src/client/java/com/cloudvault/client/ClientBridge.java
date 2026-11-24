package com.cloudvault.client;

import com.cloudvault.CloudVault;
import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupType;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.cloud.google.GoogleAuth;
import com.cloudvault.cloud.google.GoogleDriveProvider;
import com.cloudvault.cloud.s3.S3Provider;
import com.cloudvault.notification.NotificationSink;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ALL direct Minecraft-API touch points live here (screens, toasts, save/flush, browser) so that any
 * 26.x API change is fixed in one file.
 */
public final class ClientBridge {
	private ClientBridge() {}

	public static CloudVaultCore core;
	public static GoogleDriveProvider drive;
	public static S3Provider s3;
	/** Second Google account for the second destination (its own sign-in, its own token file). */
	public static GoogleDriveProvider mirrorDrive;
	private static volatile String s3Secret = "";

	/** The provider new backups are uploaded to (chosen in Settings -> Cloud). */
	public static CloudProvider provider() { return "s3".equals(core.config.cloudProvider) ? s3 : drive; }
	public static String providerName() { return "s3".equals(core.config.cloudProvider) ? "S3-compatible storage" : "Google Drive"; }

	public static void selectProvider(String id) {
		core.config.cloudProvider = "s3".equals(id) ? "s3" : "google_drive";
		core.saveConfig();
		core.setProvider(provider()); // also re-queues anything still pending
	}

	public static String s3Secret() { return s3Secret; }
	public static void setS3Secret(String secret) {
		s3Secret = secret == null ? "" : secret.trim();
		try { com.cloudvault.util.FileUtil.writeSecret(core.store.auth.resolve("s3.secret"), s3Secret); }
		catch (java.io.IOException e) { CloudVault.LOGGER.warn("Could not save the S3 secret key: {}", e.getMessage()); }
	}
	private static S3Provider.Cfg s3Cfg() {
		var c = core.config;
		return new S3Provider.Cfg(c.s3Endpoint, c.s3Region, c.s3Bucket, c.s3AccessKey, s3Secret, c.s3PathStyle);
	}

	public static Path savesDir() { return FabricLoader.getInstance().getGameDir().resolve("saves"); }
	public static Path rootDir() { return FabricLoader.getInstance().getGameDir().resolve("cloudvault"); }

	public static void init() throws Exception {
		NotificationSink sink = (level, title, msg) -> toast(title, msg);
		core = new CloudVaultCore(rootDir(), sink);
		GoogleAuth auth = new GoogleAuth(core.store.auth, com.cloudvault.cloud.google.BuiltInOAuth::clientId, com.cloudvault.cloud.google.BuiltInOAuth::clientSecret);
		auth.setUrlOpener(ClientBridge::openBrowser);
		drive = new GoogleDriveProvider(auth);
		s3 = new S3Provider(ClientBridge::s3Cfg);
		try { Path sf = core.store.auth.resolve("s3.secret"); if (java.nio.file.Files.exists(sf)) s3Secret = java.nio.file.Files.readString(sf).trim(); } catch (Exception ignored) { /* no secret saved yet */ }
		GoogleAuth auth2 = new GoogleAuth(core.store.auth, "google_tokens_mirror.json", com.cloudvault.cloud.google.BuiltInOAuth::clientId, com.cloudvault.cloud.google.BuiltInOAuth::clientSecret);
		auth2.setUrlOpener(ClientBridge::openBrowser);
		mirrorDrive = new GoogleDriveProvider(auth2) { @Override public String id() { return "google_drive_mirror"; } };
		core.cloud.registerProvider(drive);
		core.cloud.registerProvider(s3);
		core.setProvider(provider());
		core.commentPrompt = (type, world, done) -> CommentPrompts.ask(type, world, done);
		core.lockPrompt = (world, check, playAnyway) -> open(new com.cloudvault.client.gui.LockWarningScreen(world, check, playAnyway));
		applyMirror();
		Thread startup = new Thread(core::startupTasks, "CloudVault-Startup"); startup.setDaemon(true); startup.start();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> { CommentPrompts.flushAll(); core.queue.awaitIdle(30_000); core.shutdown(); }, "CloudVault-Shutdown"));
	}

	/** Use this instead of core.saveConfig() after any change in a screen: saves, applies, refreshes the settings file in the cloud, and re-points the second destination. */
	public static void settingsChanged() { core.settingsChanged(); applyMirror(); }

	/** A second Google account cannot be built by the engine: hand it the client's provider when that type is chosen. */
	public static void applyMirror() { if ("google_drive".equals(core.config.mirror.type)) core.mirror.setProvider(mirrorDrive); }

	/** Closes the open world and returns to the title screen (the "Leave world" choice of the lock warning). Minecraft API: verify in game. */
	public static void leaveWorld() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false));
	}

	public static void clipboard(String text) { Minecraft.getInstance().keyboardHandler.setClipboard(text == null ? "" : text); }
	public static String pasteboard() { String t = Minecraft.getInstance().keyboardHandler.getClipboard(); return t == null ? "" : t.trim(); }

	/** Opens a URL in the default browser via the OS command. (java.awt.Desktop is avoided: it can hang on macOS inside Minecraft.) */
	public static void openBrowser(String url) {
		if (url == null || !(url.startsWith("https://") || url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))) { CloudVault.LOGGER.warn("Refused to open a link that is not https"); return; }
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			String[] cmd = os.contains("mac") ? new String[]{"open", url}
				: os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
				: new String[]{"xdg-open", url};
			Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			if (pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && pr.exitValue() != 0)
				throw new java.io.IOException("exit code " + pr.exitValue());
		} catch (Throwable t) {
			CloudVault.LOGGER.warn("Could not open browser: {}", t.toString());
			CloudVault.LOGGER.warn("Open this sign-in link in your browser: {}", url); // only logged when the browser could not be opened
			toast("CloudVault", "Could not open the browser. The sign-in link is in logs/latest.log");
		}
	}

	public static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	// ---- GUI helpers ----
	public static void open(Screen s) { Minecraft mc = Minecraft.getInstance(); mc.execute(() -> mc.gui.setScreen(s)); }

	public static void toast(String title, String message) {
		Minecraft mc = Minecraft.getInstance();
		if (core != null && !core.config.notifications) return;
		mc.execute(() -> mc.gui.toastManager().addToast(
			new SystemToast(SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.literal(title), Component.literal(message))));
	}

	// ---- world access ----
	public static MinecraftServer server() { return Minecraft.getInstance().getSingleplayerServer(); }

	/** Flush the world to disk on the SERVER thread, then queue the backup (file copying happens on the worker thread). */
	public static boolean saveAndBackup(BackupType type) {
		MinecraftServer srv = server();
		if (srv == null || !core.inWorld()) return false;
		if (core.isWorldDisabled(core.currentWorldId())) {
			if (type == BackupType.MANUAL) toast("CloudVault", "CloudVault is turned off for this world");
			return false;
		}
		if (core.commentWanted(type)) { // a window asks for the comment first (the game is paused while it is open)
			CommentPrompts.ask(type, core.currentWorldName(), c -> { if (c != null) doBackup(type, c); });
			return true;
		}
		doBackup(type, null);
		return true;
	}

	private static void doBackup(BackupType type, String comment) {
		MinecraftServer srv = server();
		if (srv == null || !core.inWorld()) return;
		srv.execute(() -> {
			flush(srv);
			core.request(type, comment);
		});
	}

	// ---- cloud sync ----

	/** True when the folder is the world that is open right now. */
	public static boolean isOpenWorld(Path dir) {
		return core.inWorld() && core.currentWorldDir() != null && core.currentWorldDir().toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize());
	}

	private static volatile boolean syncRunning = false;
	private static volatile long lastPull = 0;

	/** Checks the cloud sync folder in the background: adds worlds this computer lacks and updates older ones. Quiet unless something happens or {@code loud}. */
	public static void syncNow(boolean loud) {
		if (syncRunning) { if (loud) toast("CloudVault", "Cloud sync is already running"); return; }
		syncRunning = true;
		Thread t = new Thread(() -> {
			try {
				var r = core.sync.pullAll(savesDir(), ClientBridge::isOpenWorld);
				lastPull = System.currentTimeMillis();
				if (loud || r.error() != null && !r.error().startsWith("Not connected")) toast("CloudVault", "Cloud sync: " + r.summary());
				else if (r.downloaded() + r.updated() + r.copies() > 0) toast("CloudVault", "Cloud sync: " + r.summary() + (r.downloaded() + r.copies() > 0 ? " (reopen the world list to see new worlds)" : ""));
			} catch (Throwable e) {
				CloudVault.LOGGER.warn("Cloud sync check failed: {}", com.cloudvault.util.ErrorText.friendly(e));
			} finally { syncRunning = false; }
		}, "CloudVault-SyncPull");
		t.setDaemon(true); t.start();
	}

	/** At most every 10 minutes: used when the world list opens, so a world synced from another computer shows up without restarting. */
	public static void syncIfStale() { if (System.currentTimeMillis() - lastPull > 600_000L) syncNow(false); }

	/** Uploads the open world to the cloud sync folder now (saves it first). Same result as leaving it. */
	public static boolean syncOpenWorldNow() {
		MinecraftServer srv = server();
		if (srv == null || !core.inWorld()) return false;
		String id = core.currentWorldId(), name = core.currentWorldName();
		java.nio.file.Path dir = core.currentWorldDir();
		if (!core.config.isWorldSynced(id)) return false;
		srv.execute(() -> { flush(srv); core.sync.requestPush(id, name, dir); });
		return true;
	}

	/** Best-effort flush of player data (inventory, position) and then all chunks. Must run on the server thread. */
	private static void flush(MinecraftServer srv) {
		try { srv.getPlayerList().saveAll(); } catch (Throwable t) { CloudVault.LOGGER.warn("Player data flush failed: {}", t.toString()); }
		try { srv.saveAllChunks(true, true, true); } catch (Throwable t) { CloudVault.LOGGER.warn("World flush failed: {}", t.toString()); }
	}

	/**
	 * Called from SERVER_STARTING, i.e. BEFORE the world is loaded (singleplayer only). If Minecraft or the mods changed since this
	 * world was last seen, the world folder is copied right now (the loading screen waits) and the rest of the backup runs in the
	 * background. Nothing happens for new worlds, switched-off worlds, or when the option is off.
	 */
	public static void worldStarting(MinecraftServer srv) {
		try {
			if (!core.config.backupBeforeUpdate || !core.config.enabled) return;
			Path dir = srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
			Path level = dir.resolve("level.dat");
			if (!Files.isRegularFile(level)) return; // a brand-new world has nothing to protect
			String folder = dir.getFileName().toString();
			String id = core.worlds.idFor(folder);
			if (core.isWorldDisabled(id)) return;
			String mc = minecraftVersion();
			List<String> mods = FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString()).toList();
			String reason = core.checkUpdate(id, com.cloudvault.restore.LevelInfo.readVersion(level), mc, com.cloudvault.backup.UpdateCheck.modsHash(mods));
			if (reason == null) return;
			String name = folder;
			try { String n = srv.getWorldData().getLevelName(); if (n != null && !n.isBlank()) name = n; } catch (Throwable ignored) { /* folder name is fine */ }
			toast("CloudVault", "Backing up before opening: " + reason);
			var req = new com.cloudvault.backup.BackupManager.Request(dir, name, id, BackupType.PRE_UPDATE, mc);
			var work = core.backups.copyNow(req); // must finish before the world loads, so it cannot change while being copied
			core.backups.submitFinish(req, work);
		} catch (Throwable t) {
			CloudVault.LOGGER.warn("Backup before update did not run: {}", com.cloudvault.util.ErrorText.friendly(t));
		}
	}

	/** Opens one of CloudVault's own folders in the system file manager. Anything outside .minecraft/cloudvault is refused. */
	public static void openFolder(Path dir) {
		try {
			Path root = rootDir().toAbsolutePath().normalize(), d = dir.toAbsolutePath().normalize();
			if (!d.startsWith(root)) return;
			Files.createDirectories(d);
			String os = System.getProperty("os.name", "").toLowerCase();
			new ProcessBuilder(os.contains("mac") ? new String[]{"open", d.toString()} : os.contains("win") ? new String[]{"explorer", d.toString()} : new String[]{"xdg-open", d.toString()}).start();
		} catch (Throwable t) {
			toast("CloudVault", "Could not open the folder: " + dir);
		}
	}

	/** Called from SERVER_STARTED (singleplayer only). */
	public static void worldStarted(MinecraftServer srv) {
		try {
			Path dir = srv.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
			String folder = dir.getFileName().toString();
			String display = srv.getWorldData().getLevelName();
			flush(srv);
			core.onWorldJoined(dir, folder, display, minecraftVersion());
			if (core.config.showWorldIndicator && core.config.notifications)
				toast("CloudVault", core.isWorldDisabled(core.currentWorldId()) ? "CloudVault is OFF for this world (turn it on: press the CloudVault key)" : "CloudVault is ON for this world");
		} catch (Throwable t) {
			CloudVault.LOGGER.error("CloudVault could not start world tracking: {}", t.toString());
		}
	}

	/** Called from SERVER_STOPPED: the world is fully saved and closed. */
	public static void worldStopped() {
		try { core.onWorldLeaving(); } catch (Throwable t) { CloudVault.LOGGER.error("Leave handling failed: {}", t.toString()); }
	}
}
