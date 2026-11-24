package com.cloudvault.config;

/** Upload controls. Backups are always made and kept on this computer; these only decide WHEN and HOW FAST they go to the cloud. */
public class UploadSettings {
	/** Hold every upload until you switch this off. */
	public boolean paused = false;
	/** Do not upload while a world is open (so uploading never slows the game); it all goes up after you leave. */
	public boolean onlyWhenNotPlaying = false;
	/** Upload speed limit in KB/s (0 = no limit). */
	public int speedLimitKBps = 0;
	/** Only upload between these hours (local time, 0-23). When start is later than end the window runs over midnight. */
	public boolean windowEnabled = false;
	public int windowStartHour = 1, windowEndHour = 7;

	public void sanitize() {
		speedLimitKBps = Math.max(0, Math.min(1_000_000, speedLimitKBps));
		windowStartHour = Math.max(0, Math.min(23, windowStartHour));
		windowEndHour = Math.max(0, Math.min(23, windowEndHour));
	}
}
