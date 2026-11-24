package com.cloudvault.scheduler;

import com.cloudvault.config.BackupSettings;
import com.cloudvault.util.TimeUtil;

import java.time.Duration;

/** Counts continuous play of ONE world. Starts on world join, resets on leave; menus/other worlds never count. */
public class GameplayScheduler {
	private boolean active;
	private long lastNanos;
	private long elapsedNanos;

	public synchronized void start() { active = true; lastNanos = System.nanoTime(); elapsedNanos = 0; }
	public synchronized void stop() { active = false; elapsedNanos = 0; }
	public synchronized boolean isActive() { return active; }

	/** Returns true when the configured play time was reached (and restarts the timer). */
	public synchronized boolean tick(BackupSettings.Gameplay g) {
		if (!active) return false;
		long now = System.nanoTime();
		elapsedNanos += Math.max(0, now - lastNanos);
		lastNanos = now;
		if (!g.enabled) return false;
		if (elapsedNanos >= TimeUtil.toDuration(g.amount, g.unit).toNanos()) { elapsedNanos = 0; return true; }
		return false;
	}

	public synchronized Duration elapsed() { return Duration.ofNanos(elapsedNanos); }

	public synchronized Duration remaining(BackupSettings.Gameplay g) {
		Duration total = TimeUtil.toDuration(g.amount, g.unit);
		Duration left = total.minusNanos(elapsedNanos);
		return left.isNegative() ? Duration.ZERO : left;
	}
}
