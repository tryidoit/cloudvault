package com.cloudvault.smart;

import com.cloudvault.config.BackupSettings;

import java.util.Optional;

/**
 * Decides when a "smart" backup is due: entering the Nether or the End, health getting low, or standing idle. The game reports what it
 * sees about once a second; this only decides. At most one smart backup per cooldown, and a trigger fires once per situation
 * (low health fires again only after health recovered).
 */
public class SmartTriggers {
	public record Observation(String dimension, float hearts, boolean moved) {}

	private String lastDim;
	private boolean lowFired, idleFired;
	private long lastFire, lastMove;

	public synchronized void reset(long now) { lastDim = null; lowFired = false; idleFired = false; lastFire = now; lastMove = now; }

	/** @return why a backup should be made now, or empty */
	public synchronized Optional<String> observe(BackupSettings s, Observation o, long now) {
		BackupSettings.Smart c = s.smart;
		String reason = null;
		if (o.dimension() != null) {
			if (lastDim == null) lastDim = o.dimension();
			else if (!lastDim.equals(o.dimension())) {
				lastDim = o.dimension();
				String d = o.dimension().toLowerCase();
				if (c.enterNether && (d.contains("the_nether") || d.contains("dim-1"))) reason = "Entered the Nether";
				else if (c.enterEnd && (d.contains("the_end") || d.contains("dim1"))) reason = "Entered the End";
			}
		}
		if (o.hearts() > 0 && o.hearts() <= c.lowHealthHearts) {
			if (!lowFired) { lowFired = true; if (reason == null && c.lowHealth) reason = "Low health (" + (Math.round(o.hearts() * 2) / 2.0) + " hearts)"; }
		} else if (o.hearts() >= c.lowHealthHearts + 3) lowFired = false;
		if (o.moved()) { lastMove = now; idleFired = false; }
		else if (!idleFired && now - lastMove >= c.idleMinutes * 60_000L) { idleFired = true; if (reason == null && c.idle) reason = "Idle for " + c.idleMinutes + " minutes"; }
		if (reason == null || !c.enabled) return Optional.empty();
		if (now - lastFire < c.cooldownMinutes * 60_000L) return Optional.empty();
		lastFire = now;
		return Optional.of(reason);
	}
}
