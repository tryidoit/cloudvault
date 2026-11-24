package com.cloudvault.config;

import com.cloudvault.util.TimeUtil.Unit;

/** The four independent backup categories. Retention: -1 = Keep All, 0 = keep only newest, 1..100 = keep that many previous. */
public class BackupSettings {
	public static final int KEEP_ALL = -1;
	public static final int MAX_RETENTION = 100;

	public static class Session {
		public boolean joinEnabled = true;
		public boolean leaveEnabled = true;
		/** Show a window to type a comment before the join / leave backup (separately). */
		public boolean joinComment = false, leaveComment = false;
		public int retention = 10;
	}
	public static class Periodic {
		public boolean enabled = true;
		public int amount = 2;
		public Unit unit = Unit.HOURS;
		public int retention = 20;
		public boolean avoidDuplicates = true;
		public boolean comment = false;
	}
	public static class Gameplay {
		public boolean enabled = true;
		public int amount = 2;
		public Unit unit = Unit.HOURS; // MINUTES..WEEKS only
		public int retention = 10;
		public boolean comment = false;
	}
	public static class Manual {
		public int retention = 50;
		public boolean confirmBeforeBackup = false;
		public boolean comment = false;
		public boolean avoidDuplicates = false; // manual backups are never skipped unless the user opts in
	}

	/** Smart triggers: extra backups at moments that matter. Only while a world is open; one at most every cooldownMinutes. */
	public static class Smart {
		public boolean enabled = true;
		public boolean enterNether = true, enterEnd = true, lowHealth = true, idle = false;
		public int lowHealthHearts = 3;   // back up when health drops to this many hearts or less (1..9)
		public int idleMinutes = 5;       // standing still this long = idle (1..60)
		public int cooldownMinutes = 10;  // never more often than this (1..240)
		public int retention = 10;
		public boolean comment = false;
	}
	/** Incremental backups: after a full backup, later ones may store only what changed. See incremental/ for the safety rules. */
	public static class Incremental {
		public boolean enabled = true;
		public int maxChain = 6;          // at most this many incremental backups after a full one (1..20)
		public int fullEveryDays = 7;     // a fresh full backup at least this often (1..90)
		public int maxChangedPercent = 50; // when more than this much of the world changed, make a full backup instead (10..90)
	}

	public Session session = new Session();
	public Periodic periodic = new Periodic();
	public Gameplay gameplay = new Gameplay();
	public Manual manual = new Manual();
	public Smart smart = new Smart();
	public Incremental incremental = new Incremental();
	/** Advanced combined retention (experimental-free, off by default); replaces the per-category numbers when enabled. */
	public RetentionPolicy advanced = new RetentionPolicy();

	/** Does this backup type ask for a comment first? Safety types (restore, reset, before update) never do. */
	public boolean commentFor(com.cloudvault.backup.BackupType t) {
		return switch (t) {
			case SESSION_JOIN -> session.joinComment;
			case SESSION_LEAVE -> session.leaveComment;
			case PERIODIC -> periodic.comment;
			case GAMEPLAY -> gameplay.comment;
			case MANUAL -> manual.comment;
			case EVENT -> smart.comment;
			default -> false;
		};
	}

	public void fixNew() {
		if (smart == null) smart = new Smart();
		if (incremental == null) incremental = new Incremental();
		smart.lowHealthHearts = Math.max(1, Math.min(9, smart.lowHealthHearts));
		smart.idleMinutes = Math.max(1, Math.min(60, smart.idleMinutes));
		smart.cooldownMinutes = Math.max(1, Math.min(240, smart.cooldownMinutes));
		smart.retention = clampRetention(smart.retention);
		incremental.maxChain = Math.max(1, Math.min(20, incremental.maxChain));
		incremental.fullEveryDays = Math.max(1, Math.min(90, incremental.fullEveryDays));
		incremental.maxChangedPercent = Math.max(10, Math.min(90, incremental.maxChangedPercent));
	}

	public static int clampRetention(int r) {
		return r == KEEP_ALL ? KEEP_ALL : Math.max(0, Math.min(MAX_RETENTION, r));
	}
}
