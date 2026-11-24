package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.google.BuiltInOAuth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Where backups go: choose Google Drive or S3-compatible storage, sign in, test. */
public class CloudConnectScreen extends BaseScreen {
	private volatile String note = "";

	public CloudConnectScreen(Screen parent) { super("Connect", parent); }
	@Override protected boolean wizardButton() { return true; }
	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		boolean s3 = "s3".equals(ClientBridge.core.config.cloudProvider);
		btn("Cloud service: " + ClientBridge.providerName() + " (click)", cx - 130, y, 260, () -> { ClientBridge.selectProvider(s3 ? "google_drive" : "s3"); note = "New backups now go to " + ClientBridge.providerName() + ". Older ones stay where they are."; rebuild(); }); y += 28;
		if (s3) {
			btn("Enter storage details...", cx - 130, y, 260, () -> this.minecraft.gui.setScreen(new S3SettingsScreen(this))); y += 24;
			btn("Test connection", cx - 130, y, 260, () -> background("test", () -> { var s = ClientBridge.s3.testConnection(); note = s.connected() ? "Connected: " + s.account() : s.message(); }));
		} else {
			var c = btn("Connect Google Drive (opens your browser)", cx - 130, y, 260, () -> { note = "Finish signing in in your browser..."; background("auth", () -> { ClientBridge.drive.authenticate(); ClientBridge.core.cloud.resumePending(); note = "Connected"; ClientBridge.toast("CloudVault", "Google Drive connected"); }); }); y += 24;
			c.active = BuiltInOAuth.available();
			btn("Test connection", cx - 130, y, 128, () -> background("test", () -> { var s = ClientBridge.drive.testConnection(); note = s.connected() ? "Connected as " + s.account() : s.message(); }));
			btn("Disconnect", cx + 2, y, 128, () -> { ClientBridge.drive.signOut(); note = "Disconnected"; });
			if (!BuiltInOAuth.available()) note = "Google sign-in is not set up in this build of the mod.";
		}
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		boolean ok = ClientBridge.provider().isAuthenticated();
		centered(g, ok ? "Status: connected" : "Status: not connected", 28, ok ? GREEN : YELLOW);
		if (!note.isEmpty()) centered(g, Fmt.clip(note, 70), this.height - 48, YELLOW);
	}
}
