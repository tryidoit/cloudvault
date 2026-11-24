package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.lock.LockManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shown when a cloud-synced world is open (or was left unfinished) on another computer. "Leave world" closes it WITHOUT uploading this
 * computer's untouched copy over the other computer's newer one; "Play anyway" takes the world over.
 */
public class LockWarningScreen extends BaseScreen {
	private final String world; private final LockManager.Check check; private final Runnable playAnyway;

	public LockWarningScreen(String world, LockManager.Check check, Runnable playAnyway) { super("World in use", null); this.world = world; this.check = check; this.playAnyway = playAnyway; }

	@Override protected boolean showCrumbs() { return false; }
	/** No way out with Escape: the player has to choose. */
	@Override public void onClose() { }
	@Override public boolean shouldCloseOnEsc() { return false; }

	@Override protected void init() {
		int cx = this.width / 2, y = this.height / 2 + 24;
		btn("Leave world (safe)", cx - 105, y, 100, () -> { ClientBridge.core.skipSyncOnLeave(); ClientBridge.leaveWorld(); }, "Closes the world. Nothing is uploaded, so the other computer's newer copy is not overwritten.");
		btn("Play anyway", cx + 5, y, 100, () -> { this.minecraft.gui.setScreen(null); background("lock-takeover", playAnyway::run); }, "Takes the world over. Changes made on the other computer in the meantime cannot be merged with yours.");
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int y = this.height / 2 - 50;
		boolean stale = check.state() == LockManager.State.STALE_OTHER;
		long mins = Math.max(0, (System.currentTimeMillis() - check.lastSeen()) / 60_000L);
		centered(g, "\"" + world + "\" " + (stale ? "was opened on " + check.device() : "is open on " + check.device()), y, YELLOW); y += 14;
		centered(g, "Last sign of life: " + (mins < 1 ? "just now" : mins + " minute(s) ago"), y, GREY); y += 14;
		centered(g, stale ? "It may not have finished saving to the cloud. Check that computer first." : "Playing here too would create two versions that cannot be merged.", y, WHITE); y += 14;
		centered(g, "Choose what to do:", y, GREY);
	}
}
