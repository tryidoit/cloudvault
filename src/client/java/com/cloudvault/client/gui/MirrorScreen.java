package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.google.BuiltInOAuth;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Second destination: what the settings page cannot hold: the S3 secret key, a second Google account, a test, and "copy what is missing". */
public class MirrorScreen extends BaseScreen {
	private volatile String note = "";
	private String newSecret = "";

	public MirrorScreen(Screen parent) { super("Second destination: keys and test", parent); }
	@Override protected boolean wizardButton() { return true; }
	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		String type = ClientBridge.core.config.mirror.type;
		if (type.equals("s3")) {
			EditBox box = new EditBox(this.font, cx - 130, y + 12, 260, 20, Component.literal("Secret key"));
			box.setMaxLength(200); box.setResponder(v -> newSecret = v); this.addRenderableWidget(box); y += 40;
			btn("Save secret key", cx - 130, y, 260, () -> { if (!newSecret.isBlank()) { ClientBridge.core.secrets.set("mirror.s3.secret", newSecret.trim()); newSecret = ""; ClientBridge.settingsChanged(); note = "Secret key saved"; rebuild(); } }); y += 24;
		} else if (type.equals("google_drive")) {
			var c = btn("Sign in to the second Google account (browser)", cx - 130, y, 260, () -> { note = "Finish signing in in your browser..."; background("mirror-auth", () -> { ClientBridge.mirrorDrive.authenticate(); ClientBridge.applyMirror(); note = "Connected"; }); }, "Use a different Google account than the first destination.");
			c.active = BuiltInOAuth.available(); y += 24;
			btn("Sign out of the second account", cx - 130, y, 260, () -> { ClientBridge.mirrorDrive.signOut(); note = "Signed out"; }); y += 24;
		} else { y += 4; }
		btn("Test the second destination", cx - 130, y, 260, () -> background("mirror-test", () -> {
			var p = ClientBridge.core.mirror.provider();
			if (p == null) { note = "Not set up yet"; return; }
			var s = p.testConnection(); note = s.connected() ? "Works" + (s.account() == null || s.account().isBlank() ? "" : ": " + s.account()) : s.message();
		})); y += 24;
		btn("Copy missing backups there now", cx - 130, y, 260, () -> background("mirror-backfill", () -> { ClientBridge.core.mirror.backfill(); note = "Copying started in the background"; }), "Every backup is copied automatically; this checks for ones that are still missing.");
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override public void onClose() { if (!newSecret.isBlank()) { ClientBridge.core.secrets.set("mirror.s3.secret", newSecret.trim()); newSecret = ""; } super.onClose(); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		var m = ClientBridge.core.config.mirror;
		centered(g, m.enabled ? "Second destination is ON (" + m.type + ")" : "Second destination is OFF (switch it on on the page before)", 26, m.enabled ? GREEN : GREY);
		if (m.type.equals("s3")) left(g, ClientBridge.core.secrets.has("mirror.s3.secret") ? "Secret key (saved - type to replace)" : "Secret key", this.width / 2 - 130, 48, GREY);
		if (!note.isEmpty()) centered(g, Fmt.clip(note, 70), this.height - 48, YELLOW);
	}
}
