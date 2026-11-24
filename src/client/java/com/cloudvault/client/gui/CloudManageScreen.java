package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.cloud.StorageManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Cloud status, usage, refresh, open folder, reconnect / switch account. All network work runs off the game thread. */
public class CloudManageScreen extends BaseScreen {
	@Override protected boolean wizardButton() { return true; }
	private volatile String account = "Checking...", usage = "", counts = "";
	private volatile boolean connected = false;
	private volatile boolean busy = false;

	public CloudManageScreen(Screen parent) { super("Cloud Management", parent); }

	private CloudVaultCore core() { return ClientBridge.core; }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 92, w = 154;
		btn("Refresh cloud contents", cx - w - 3, y, w, this::refreshCloud);
		btn("Open cloud folder", cx + 3, y, w, this::openFolder); y += 24;
		btn("Reconnect account", cx - w - 3, y, w, this::reconnect);
		btn("Switch account", cx + 3, y, w, this::switchAccount); y += 24;
		btn("Retry pending/failed uploads", cx - w - 3, y, w, () -> { int n = core().cloud.retryAll(); ClientBridge.toast("CloudVault", n == 0 ? "Nothing to retry" : "Retrying " + n + " backup(s)"); });
		btn("Test connection", cx + 3, y, w, this::loadInfo); y += 24;
		btn("Cloud settings...", cx - w - 3, y, w, () -> this.minecraft.gui.setScreen(PageTree.open(this, "Cloud/Storage")));
		btn("Storage...", cx + 3, y, w, () -> this.minecraft.gui.setScreen(new StorageScreen(this))); y += 24;
		btn("Import backups from cloud...", cx - w - 3, y, w, () -> this.minecraft.gui.setScreen(new ImportScreen(this)));
		btn("Worlds and reset...", cx + 3, y, w, () -> this.minecraft.gui.setScreen(new WorldsScreen(this)));
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
		loadInfo();
	}

	private void loadInfo() {
		background("cloud-info", () -> {
			CloudProvider p = ClientBridge.provider();
			account = "Checking...";
			CloudProvider.Status s = p.testConnection();
			connected = s.connected();
			account = s.connected() ? (s.account() == null ? "Connected" : s.account()) : s.message();
			StorageManager.Report r = core().storage.report();
			counts = r.verified() + " verified in cloud (" + StorageManager.human(r.cloudBytes()) + "), " + r.pending() + " pending, " + r.failed() + " failed";
			usage = r.account() == null ? "Account storage: not available"
				: "Account storage: " + StorageManager.human(r.account().used()) + (r.account().total() < 0 ? " used" : " of " + StorageManager.human(r.account().total()));
		});
	}

	private void guarded(String name, Job j) {
		if (busy) { ClientBridge.toast("CloudVault", "Please wait, still working..."); return; }
		busy = true;
		background(name, () -> { try { j.run(); } finally { busy = false; } });
	}

	private void refreshCloud() {
		guarded("cloud-refresh", () -> {
			var r = core().cloud.refresh();
			ClientBridge.toast("CloudVault", r.cloudFiles() + " backup(s) in cloud (" + StorageManager.human(r.cloudBytes()) + ")"
				+ (r.missing() > 0 ? ", " + r.missing() + " missing - re-uploading" : "") + (r.unknown() > 0 ? ", " + r.unknown() + " not from this computer" : ""));
			loadInfo();
		});
	}

	private void openFolder() {
		guarded("cloud-folder", () -> {
			String url = ClientBridge.provider().folderUrl();
			if (url == null) ClientBridge.toast("CloudVault", "This storage has no web folder - use your provider's dashboard");
			else ClientBridge.openBrowser(url);
		});
	}

	private void reconnect() {
		guarded("cloud-reconnect", () -> {
			ClientBridge.provider().authenticate();
			core().cloud.resumePending();
			ClientBridge.toast("CloudVault", ClientBridge.providerName() + " connected");
			loadInfo();
		});
	}

	private void switchAccount() {
		if ("s3".equals(core().config.cloudProvider)) { this.minecraft.gui.setScreen(new S3SettingsScreen(this)); return; }
		guarded("cloud-switch", () -> {
			ClientBridge.provider().signOut(); // sign-in below lets you pick a different Google account
			ClientBridge.provider().authenticate();
			core().cloud.resumePending();
			ClientBridge.toast("CloudVault", "Signed in");
			loadInfo();
		});
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int x = this.width / 2 - 157, y = 28;
		left(g, "Provider: " + ClientBridge.providerName(), x, y, WHITE); y += 12;
		left(g, (connected ? "Connected: " : "Not connected: ") + account, x, y, connected ? GREEN : YELLOW); y += 12;
		left(g, usage, x, y, GREY); y += 12;
		left(g, counts, x, y, GREY); y += 12;
		if (busy) left(g, "Working...", x, y, YELLOW);
	}
}
