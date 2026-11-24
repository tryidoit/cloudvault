package com.cloudvault.config;

/** CloudVault account: this computer keeps a settings file in your cloud storage so a new computer can set itself up. */
public class AccountSettings {
	public boolean enabled = false;
	/** Shown in the cloud file name. Empty = the computer's name. */
	public String deviceName = "";
	/** Stable id of this computer (made once). */
	public String deviceId = java.util.UUID.randomUUID().toString();
	/** The cloud settings file this computer last took its settings from (display only). */
	public String importedFrom = "";
	public long lastUploadAt = 0;
}
