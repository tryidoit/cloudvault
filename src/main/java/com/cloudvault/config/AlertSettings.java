package com.cloudvault.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Failure alerts sent OUTSIDE the game (phone, chat, email). Only the on/off choices live here: every address, number, token and
 * webhook is kept in the secret store (auth/), never in config.json.
 */
public class AlertSettings {
	public boolean enabled = false;
	public boolean onBackupFailed = true, onUploadFailed = true, onNoRecentBackup = true, onLowSpace = true, onSyncProblem = true, onRestoreTestFailed = true, onWorldTakeover = true;
	/** Alert when no backup has succeeded for this many days (0 = off). Checked while playing, about hourly. */
	public int noBackupDays = 3;
	/** The same alert is not sent again within this many minutes. */
	public int minMinutesBetween = 30;
	public List<Channel> channels = new ArrayList<>();

	/** type: discord, slack, telegram, ntfy, pushover, sms, email, webhook. Its details are in the secret store under "alert.<id>.<field>". */
	public static class Channel {
		public String id = java.util.UUID.randomUUID().toString().substring(0, 8);
		public String type = "discord";
		public String name = "";
		public boolean enabled = true;
	}

	public void sanitize() {
		if (channels == null) channels = new ArrayList<>();
		channels.removeIf(c -> c == null || c.type == null || c.id == null);
		noBackupDays = Math.max(0, Math.min(365, noBackupDays));
		minMinutesBetween = Math.max(1, Math.min(1440, minMinutesBetween));
	}
}
