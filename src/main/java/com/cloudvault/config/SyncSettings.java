package com.cloudvault.config;

/** Global cloud sync defaults. Each world can override "on/off" and "copies kept" (WorldSettings). */
public class SyncSettings {
	public static final int DEFAULT_KEEP = 2, MAX_KEEP = 20;
	/** Worlds without their own choice follow this. Off by default: sync only runs for worlds you switch on. */
	public boolean newWorldsSync = false;
	/** How many sync copies of one world stay in the cloud sync folder (the newest N). */
	public int keep = DEFAULT_KEEP;
	/** When Minecraft starts, bring in synced worlds this computer does not have yet and update ones that are older. */
	public boolean autoDownload = true;

	public static int clampKeep(int k) { return Math.max(1, Math.min(MAX_KEEP, k)); }
}
