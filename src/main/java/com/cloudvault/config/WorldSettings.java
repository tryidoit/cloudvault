package com.cloudvault.config;

/** Per-world override. When useOverride is false the global defaults apply. */
public class WorldSettings {
	public String lastKnownName = "";
	/** Explicit choice: true = CloudVault on for this world, false = off (no automatic or manual backups, no uploads; safety backups before a restore or reset still run). null = follow "new worlds start with CloudVault". */
	public Boolean enabled = null;
	/** Old format (before "enabled" existed). Read once and converted by CloudVaultConfig.sanitize(). */
	public boolean disabled = false;
	/** Last Minecraft version / mod set a "before update" check has seen for this world. */
	public String lastMcVersion = "", lastModsHash = "";
	/** Cloud sync for this world: true/false = explicit choice, null = follow SyncSettings.newWorldsSync. */
	public Boolean syncEnabled = null;
	/** Sync copies kept for this world; null = follow the global number. */
	public Integer syncKeep = null;
	/** Identifies this world across computers (the same value on every computer that syncs it). Created when sync first runs. */
	public String syncId = null;
	/** Cloud file this computer last uploaded or downloaded for the world; fingerprint of the world at that moment. */
	public String lastSyncRemoteId = null, syncLocalFp = null;
	/** A newer cloud version that was already added as a separate copy (so it is not added again every start). */
	public String conflictRemoteId = null;
	/** The leave upload has not finished yet (offline, game closed): it is retried at the next start. */
	public boolean syncDirty = false;
	public long lastSyncAt = 0;
	public boolean useOverride = false;
	public BackupSettings settings = new BackupSettings();
}
