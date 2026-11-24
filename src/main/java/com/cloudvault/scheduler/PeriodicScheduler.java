package com.cloudvault.scheduler;

import com.cloudvault.config.BackupSettings;
import com.cloudvault.util.TimeUtil;

import java.time.ZonedDateTime;

/** Calendar-aware periodic timer. Only runs while a world is open; never creates a backlog of missed backups. */
public class PeriodicScheduler {
	private ZonedDateTime nextDue;

	/** lastBackup may be null. If the interval already elapsed while offline, ONE catch-up is due after a short delay. */
	public synchronized void start(ZonedDateTime lastBackup, BackupSettings.Periodic p, ZonedDateTime now) {
		ZonedDateTime base = lastBackup == null ? now : lastBackup;
		ZonedDateTime due = TimeUtil.addInterval(base, p.amount, p.unit);
		nextDue = due.isAfter(now) ? due : now.plusMinutes(1);
	}

	public synchronized void stop() { nextDue = null; }
	public synchronized ZonedDateTime nextDue() { return nextDue; }

	/** True when a backup should be triggered now; reschedules from "now" (skipping any missed slots). */
	public synchronized boolean tick(BackupSettings.Periodic p, ZonedDateTime now) {
		if (nextDue == null || !p.enabled) return false;
		if (now.isBefore(nextDue)) return false;
		nextDue = TimeUtil.addInterval(now, p.amount, p.unit);
		return true;
	}

	/** Call after the user edits the interval so the new value takes effect. */
	public synchronized void reschedule(BackupSettings.Periodic p, ZonedDateTime now) {
		if (nextDue != null) nextDue = TimeUtil.addInterval(now, p.amount, p.unit);
	}
}
