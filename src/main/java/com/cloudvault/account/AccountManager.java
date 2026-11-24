package com.cloudvault.account;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.config.WorldSettings;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.DeviceInfo;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.FileUtil;
import com.cloudvault.util.SecretBox;
import com.cloudvault.util.SecretStore;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * CloudVault account = a settings file this computer keeps in YOUR cloud storage (folder "CloudVault Account"). A new computer that signs
 * in to the same storage can set itself up from one of those files. Every computer has its own file, named after the computer, and it is
 * rewritten a few seconds after each settings change. Keys, webhooks and phone numbers are only included when you set an account password
 * (they are encrypted with it); the backup-encryption key and your cloud login are never included.
 */
public class AccountManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Not copied between computers: it describes THIS computer, its cloud login or the world list. */
	private static final Set<String> LOCAL_ONLY = Set.of("worlds", "account", "firstLaunchDone", "autoPausedUntil", "cloudProvider", "s3Endpoint", "s3Region", "s3Bucket", "s3AccessKey", "s3PathStyle");
	private static final String FOLDER = "Settings";

	public record Profile(String fileId, String fileName, String deviceName, String os, String minecraft, String mod, long savedAt, boolean hasSecrets, boolean mine) {}
	public record Result(boolean ok, boolean needsPassword, String message) {}

	private final CloudVaultConfig config;
	private final CloudManager cloud;
	private final SecretStore secrets;
	private final BackupStore store;
	private final NotificationSink sink;
	private final Runnable saveConfig;
	private final Path pwFile;
	private volatile String mcVersion = "", ownFileId;
	private volatile boolean applying;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CloudVault-Account"); t.setDaemon(true); return t; });
	private ScheduledFuture<?> pending;
	private final Map<String, JsonObject> pendingWorlds = new HashMap<>();

	public AccountManager(CloudVaultConfig config, CloudManager cloud, SecretStore secrets, BackupStore store, NotificationSink sink, Runnable saveConfig) {
		this.config = config; this.cloud = cloud; this.secrets = secrets; this.store = store; this.sink = sink; this.saveConfig = saveConfig;
		this.pwFile = store.auth.resolve("account.pw");
	}

	public void setMcVersion(String v) { mcVersion = v == null ? "" : v; }
	public boolean enabled() { return config.account.enabled; }

	public String password() { try { return Files.exists(pwFile) ? Files.readString(pwFile).trim() : ""; } catch (IOException e) { return ""; } }
	public void setPassword(String pw) {
		try { if (pw == null || pw.isBlank()) Files.deleteIfExists(pwFile); else FileUtil.writeSecret(pwFile, pw.trim()); } catch (IOException e) { CloudVault.LOGGER.warn("Could not save the account password: {}", e.getMessage()); }
	}

	private CloudProvider provider() throws IOException {
		CloudProvider p = cloud.provider();
		if (p == null || !p.isAuthenticated()) throw new com.cloudvault.cloud.CloudException(com.cloudvault.cloud.CloudException.Kind.AUTH, "Connect your cloud storage first");
		return p;
	}

	public String ownFileName() {
		String dev = DeviceInfo.displayName(config.account.deviceName).replaceAll("[^A-Za-z0-9_.-]+", "-");
		if (dev.length() > 30) dev = dev.substring(0, 30);
		return "settings_" + dev + "_" + DeviceInfo.os() + "_" + config.account.deviceId.substring(0, 8) + ".json";
	}

	// ---------------------------------------------------------------- file content

	private JsonObject exportConfig() {
		JsonObject c = GSON.toJsonTree(config).getAsJsonObject();
		for (String k : LOCAL_ONLY) c.remove(k);
		return c;
	}

	private JsonObject exportWorlds() {
		JsonObject w = new JsonObject();
		for (WorldSettings ws : config.worlds.values()) if (ws.syncId != null && !ws.syncId.isBlank()) {
			WorldSettings copy = GSON.fromJson(GSON.toJson(ws), WorldSettings.class);
			copy.lastSyncRemoteId = null; copy.syncLocalFp = null; copy.conflictRemoteId = null; copy.syncDirty = false; copy.lastSyncAt = 0; // this computer's sync state stays here
			w.add(ws.syncId, GSON.toJsonTree(copy));
		}
		return w;
	}

	String build() throws Exception {
		JsonObject root = new JsonObject();
		root.addProperty("format", 1);
		JsonObject dev = new JsonObject();
		dev.addProperty("id", config.account.deviceId); dev.addProperty("name", DeviceInfo.displayName(config.account.deviceName)); dev.addProperty("os", DeviceInfo.os());
		dev.addProperty("minecraft", mcVersion); dev.addProperty("mod", CloudVault.VERSION); dev.addProperty("savedAt", System.currentTimeMillis());
		root.add("device", dev);
		root.add("config", exportConfig());
		root.add("worlds", exportWorlds());
		String pw = password();
		if (!pw.isEmpty() && !secrets.snapshot().isEmpty()) {
			byte[] sealed = SecretBox.seal(pw.toCharArray(), GSON.toJson(secrets.snapshot()).getBytes(StandardCharsets.UTF_8));
			root.addProperty("secrets", Base64.getEncoder().encodeToString(sealed));
		}
		return GSON.toJson(root);
	}

	// ---------------------------------------------------------------- upload (automatic)

	/** Call after any setting changed. The file is rewritten a few seconds later (several quick changes make one upload). */
	public synchronized void markDirty() {
		if (!config.account.enabled || applying) return;
		if (pending != null) pending.cancel(false);
		pending = timer.schedule(() -> { try { uploadNow(); } catch (Exception e) { CloudVault.LOGGER.warn("Could not update the settings file in the cloud: {}", ErrorText.redact(String.valueOf(e.getMessage()))); } }, 4, TimeUnit.SECONDS);
	}

	/** Writes this computer's settings file to the cloud now (blocking). */
	public synchronized void uploadNow() throws Exception {
		CloudProvider p = provider();
		Path tmp = store.temp.resolve("account-" + UUID.randomUUID() + ".json");
		try {
			Files.writeString(tmp, build(), StandardCharsets.UTF_8);
			String old = ownFileId;
			if (old == null) for (Profile pr : listProfiles()) if (pr.mine()) old = pr.fileId();
			CloudProvider.RemoteFile rf = p.putFile("account", FOLDER, ownFileName(), tmp, Map.of("device", config.account.deviceId.substring(0, 8)));
			ownFileId = rf.id();
			if (old != null && !old.equals(rf.id())) try { p.deleteBackup(old); } catch (Exception ignored) { /* an old copy is harmless: the newest wins */ }
			config.account.lastUploadAt = System.currentTimeMillis();
			saveConfig.run();
		} finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp */ } }
	}

	// ---------------------------------------------------------------- discovery and import

	private JsonObject readJson(CloudProvider p, String fileId) throws IOException {
		Path tmp = store.temp.resolve("account-dl-" + UUID.randomUUID() + ".json");
		try { p.downloadBackup(fileId, tmp); return JsonParser.parseString(Files.readString(tmp, StandardCharsets.UTF_8)).getAsJsonObject(); }
		catch (JsonParseException | IllegalStateException e) { throw new IOException("Not a CloudVault settings file"); }
		finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* temp */ } }
	}

	/** The settings files in the cloud (this computer's own is marked). Blocking. */
	public List<Profile> listProfiles() throws IOException {
		CloudProvider p = provider();
		List<Profile> out = new ArrayList<>();
		for (CloudProvider.RemoteFile f : p.listFiles("account", FOLDER, "settings_")) {
			try {
				JsonObject o = readJson(p, f.id());
				JsonObject d = o.getAsJsonObject("device");
				boolean mine = d.get("id").getAsString().equals(config.account.deviceId);
				out.add(new Profile(f.id(), f.name(), d.get("name").getAsString(), d.has("os") ? d.get("os").getAsString() : "", d.has("minecraft") ? d.get("minecraft").getAsString() : "", d.has("mod") ? d.get("mod").getAsString() : "",
					d.has("savedAt") ? d.get("savedAt").getAsLong() : 0, o.has("secrets"), mine));
			} catch (Exception ignored) { /* not ours or damaged: skip */ }
		}
		out.sort(Comparator.comparingLong(Profile::savedAt).reversed());
		return out;
	}

	/** Copies the settings of one cloud file onto this computer. Never touches the cloud login, the world list or the backup-encryption key. */
	public Result applyProfile(Profile prof, String passwordForSecrets) {
		applying = true;
		try {
			CloudProvider p = provider();
			JsonObject o = readJson(p, prof.fileId());
			Map<String, String> imported = null;
			if (o.has("secrets")) {
				if (passwordForSecrets == null || passwordForSecrets.isBlank()) { if (!password().isEmpty()) passwordForSecrets = password(); }
				if (passwordForSecrets != null && !passwordForSecrets.isBlank()) {
					try { imported = GSON.fromJson(new String(SecretBox.open(passwordForSecrets.toCharArray(), Base64.getDecoder().decode(o.get("secrets").getAsString())), StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType()); }
					catch (java.security.GeneralSecurityException e) { return new Result(false, true, "Wrong account password"); }
				} else return new Result(false, true, "This settings file has keys in it. Enter the account password.");
			}
			CloudVaultConfig incoming = GSON.fromJson(o.getAsJsonObject("config"), CloudVaultConfig.class);
			if (incoming == null) return new Result(false, false, "The settings file is empty");
			incoming.sanitize();
			for (Field f : CloudVaultConfig.class.getFields()) {
				if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || LOCAL_ONLY.contains(f.getName())) continue;
				try { f.set(config, f.get(incoming)); } catch (IllegalAccessException ignored) { /* skip */ }
			}
			if (imported != null) secrets.putAll(imported);
			if (o.has("worlds")) for (var e : o.getAsJsonObject("worlds").entrySet()) pendingWorlds.put(e.getKey(), e.getValue().getAsJsonObject());
			for (WorldSettings ws : config.worlds.values()) if (ws.syncId != null) applyWorld(ws.syncId, ws);
			if (imported != null && passwordForSecrets != null && !passwordForSecrets.isBlank() && password().isEmpty()) setPassword(passwordForSecrets);
			config.firstLaunchDone = true;
			config.account.enabled = true;
			config.account.importedFrom = prof.deviceName() + " (" + prof.os() + ")";
			config.sanitize();
			saveConfig.run();
			if (config.notifications) sink.notify(NotificationSink.Level.SUCCESS, "CloudVault", "Settings loaded from " + prof.deviceName());
			return new Result(true, false, "Settings loaded from " + prof.deviceName());
		} catch (Exception e) {
			return new Result(false, false, ErrorText.friendly(e));
		} finally { applying = false; }
	}

	/** A synced world that appears on this computer later gets the settings the account file had for it. */
	public synchronized void applyWorld(String syncId, WorldSettings ws) {
		JsonObject w = pendingWorlds.remove(syncId);
		if (w == null) return;
		try {
			WorldSettings in = GSON.fromJson(w, WorldSettings.class);
			ws.useOverride = in.useOverride; if (in.settings != null) ws.settings = in.settings;
			ws.syncEnabled = in.syncEnabled; ws.syncKeep = in.syncKeep; if (in.enabled != null) ws.enabled = in.enabled;
		} catch (RuntimeException ignored) { /* keep the defaults */ }
	}

	/** Turns the account on: this computer's settings file is created in the cloud. */
	public void enable() throws Exception {
		config.account.enabled = true;
		try { uploadNow(); } catch (Exception e) { config.account.enabled = false; throw e; }
	}

	/** Turns the account off and removes this computer's settings file from the cloud. */
	public void disable(boolean deleteCloudFile) {
		config.account.enabled = false;
		if (deleteCloudFile) try { CloudProvider p = provider(); for (Profile pr : listProfiles()) if (pr.mine()) p.deleteBackup(pr.fileId()); ownFileId = null; } catch (Exception ignored) { /* the file can be removed by hand */ }
		saveConfig.run();
	}

	public void deleteProfile(Profile p) throws IOException { provider().deleteBackup(p.fileId()); if (p.fileId().equals(ownFileId)) ownFileId = null; }

	public void shutdown() { timer.shutdownNow(); }
}
