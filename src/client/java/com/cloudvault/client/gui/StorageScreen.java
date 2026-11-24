package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.StorageManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** Local and cloud storage numbers plus cleanup tools. Nothing here removes a backup that is not safely in the cloud. */
public class StorageScreen extends BaseScreen {
	@Override protected boolean wizardButton() { return true; }
	private volatile StorageManager.Report report;

	public StorageScreen(Screen parent) { super("Storage", parent); }

	private CloudVaultCore core() { return ClientBridge.core; }

	@Override protected void init() {
		header();
		int cx = this.width / 2, w = 240, y = this.height - 124;
		btn("Free disk space: remove local copies of uploaded backups", cx - w / 2, y, w, () -> this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Free disk space?",
			List.of("Deletes the local ZIP of every backup that is verified in the cloud.", "They stay in the cloud and download again when needed."), "Free space", false,
			() -> background("storage-free", () -> { long b = core().storage.clearLocalCopiesOfUploaded(); ClientBridge.toast("CloudVault", "Freed " + StorageManager.human(b)); load(); })))); y += 24;
		btn("Clean temporary files", cx - w / 2, y, w, () -> background("storage-temp", () -> {
			if (core().queue.size() > 0) { ClientBridge.toast("CloudVault", "Wait until backups finish, then try again"); return; }
			long b = core().storage.cleanTemp(); ClientBridge.toast("CloudVault", "Freed " + StorageManager.human(b)); load();
		})); y += 24;
		btn("Retry all pending/failed uploads", cx - w / 2, y, w, () -> { int n = core().cloud.retryAll(); ClientBridge.toast("CloudVault", n == 0 ? "Nothing to retry" : "Retrying " + n + " backup(s)"); });
		btn("Open backups folder", cx - 121, this.height - 50, 119, () -> ClientBridge.openFolder(ClientBridge.rootDir().resolve("backups")));
		btn("Open exports folder", cx + 2, this.height - 50, 119, () -> ClientBridge.openFolder(ClientBridge.rootDir().resolve("exports")));
		btn("Refresh numbers", cx - 50 - 55, this.height - 26, 100, this::load);
		btn("Done", cx + 5, this.height - 26, 100, this::onClose);
		load();
	}

	private void load() { background("storage-report", () -> report = core().storage.report()); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		StorageManager.Report r = report;
		int x = this.width / 2 - 120, y = 28;
		if (r == null) { left(g, "Reading storage...", x, y, GREY); return; }
		left(g, "On this computer", x, y, WHITE); y += 12;
		left(g, r.localFiles() + " backup file(s), " + StorageManager.human(r.localBytes()) + "   (temp files: " + StorageManager.human(r.tempBytes()) + ")", x + 6, y, GREY); y += 16;
		left(g, "In the cloud (" + ClientBridge.providerName() + ")", x, y, WHITE); y += 12;
		left(g, r.verified() + " verified, " + StorageManager.human(r.cloudBytes()) + (r.encrypted() > 0 ? "  (" + r.encrypted() + " encrypted)" : ""), x + 6, y, GREEN); y += 11;
		if (r.account() != null) { left(g, "Account: " + StorageManager.human(r.account().used()) + (r.account().total() < 0 ? " used" : " of " + StorageManager.human(r.account().total())), x + 6, y, GREY); y += 11; }
		y += 5;
		left(g, "Status of all backups", x, y, WHITE); y += 12;
		left(g, "Verified: " + r.verified() + "   Local only: " + r.localOnly(), x + 6, y, GREY); y += 11;
		left(g, "Pending: " + r.pending(), x + 6, y, r.pending() > 0 ? YELLOW : GREY); y += 11;
		left(g, "Failed: " + r.failed(), x + 6, y, r.failed() > 0 ? RED : GREY);
	}
}
