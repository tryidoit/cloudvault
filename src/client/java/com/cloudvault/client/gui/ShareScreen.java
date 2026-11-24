package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.share.ShareManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Share one backup as a link/code that expires. The other person pastes it in Import. Needs the cloud to be connected. */
public class ShareScreen extends BaseScreen {
	private static final int[] DAYS = {1, 3, 7, 30};
	private BackupMetadata pick;
	private int dayIdx = 2;
	private volatile String lastCode = "", note = "";

	public ShareScreen(Screen parent) { super("Share a backup", parent); }

	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		btn("Backup: " + (pick == null ? "choose..." : Fmt.clip(Fmt.describe(pick), 40)), cx - 150, 24, 300, () -> this.minecraft.gui.setScreen(new BackupPickerScreen(this, "Pick the backup to share", x -> { pick = x; lastCode = ""; })));
		btn("Link works for: " + DAYS[dayIdx] + " day(s) (click)", cx - 150, 48, 300, () -> { dayIdx = (dayIdx + 1) % DAYS.length; rebuild(); }, "After this the link stops working and the shared copy is removed from your cloud.");
		var mk = btn("Create share", cx - 150, 72, 146, () -> {
			note = "Creating... (uploads the backup if needed)"; lastCode = "";
			background("share", () -> { try { lastCode = ClientBridge.core.shares.create(pick, DAYS[dayIdx]); note = "Created. Copy the code and send it."; } catch (Exception e) { note = "Could not share: " + Fmt.clip(e.getMessage(), 60); } rebuildLater(); });
		}, "Anyone with the code can download this world until it expires.");
		mk.active = pick != null;
		btn("Copy code", cx + 4, 72, 146, () -> { ClientBridge.clipboard(lastCode); note = "Copied."; }).active = !lastCode.isEmpty();
		int y = 130;
		for (ShareManager.Share s : ClientBridge.core.shares.list()) {
			if (y > this.height - 60) break;
			btn("Copy", cx + 40, y, 50, () -> { try { ClientBridge.clipboard(s.link); } catch (RuntimeException ignored) { /* nothing to copy */ } }, "Copies the link of this share.");
			btn("Stop sharing", cx + 94, y, 80, () -> background("unshare", () -> { try { ClientBridge.core.shares.revoke(s); } catch (Exception e) { note = "Could not remove: " + Fmt.clip(e.getMessage(), 50); } rebuildLater(); }), "Removes the shared copy; the link stops working.");
			y += 22;
		}
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	private void rebuildLater() { this.minecraft.execute(this::rebuild); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int x = this.width / 2 - 150;
		if (!note.isEmpty()) left(g, note, x, 100, note.startsWith("Could") ? RED : YELLOW);
		left(g, "Anyone who has the code can download the world.", x, 112, GREY);
		int y = 130;
		for (ShareManager.Share s : ClientBridge.core.shares.list()) {
			if (y > this.height - 60) break;
			long days = Math.max(0, (s.expires - System.currentTimeMillis()) / 86_400_000L);
			left(g, Fmt.clip(s.worldName, 18) + " (" + Fmt.size(s.size) + ", " + days + "d left)", x, y + 6, WHITE);
			y += 22;
		}
	}
}
