package com.cloudvault.config;

/** World-in-use lock for cloud-synced worlds: warns before you open a world another computer has open (or has not finished uploading). */
public class LockSettings {
	public boolean enabled = true;
	/** A lock whose computer has not renewed it for this long counts as stale (that computer probably crashed or lost power). */
	public int staleMinutes = 10;
	public void sanitize() { staleMinutes = Math.max(2, Math.min(120, staleMinutes)); }
}
