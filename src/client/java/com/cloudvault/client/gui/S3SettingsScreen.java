package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.config.CloudVaultConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Settings for S3-compatible storage (Cloudflare R2, Backblaze B2, AWS S3, MinIO...). The secret key is kept in auth/, not config.json. */
public class S3SettingsScreen extends BaseScreen {
	@Override protected boolean wizardButton() { return true; }
	private String newSecret = "";
	private String note = "";

	public S3SettingsScreen(Screen parent) { super("S3-compatible storage", parent); }

	private CloudVaultConfig cfg() { return ClientBridge.core.config; }

	private EditBox box(int x, int y, int w, String name, String value, Consumer<String> set) {
		EditBox b = new EditBox(this.font, x, y, w, 20, Component.literal(name));
		b.setMaxLength(300); b.setValue(value); b.setResponder(s -> set.accept(s.trim()));
		this.addRenderableWidget(b);
		return b;
	}

	@Override protected void init() {
		header();
		int cx = this.width / 2, l = cx - 155;
		box(l, 50, 310, "Endpoint", cfg().s3Endpoint, v -> cfg().s3Endpoint = v);
		box(l, 88, 150, "Region", cfg().s3Region, v -> cfg().s3Region = v);
		box(l + 160, 88, 150, "Bucket", cfg().s3Bucket, v -> cfg().s3Bucket = v);
		box(l, 126, 150, "Access key ID", cfg().s3AccessKey, v -> cfg().s3AccessKey = v);
		box(l + 160, 126, 150, "Secret key", "", v -> newSecret = v);
		btn("Path-style addressing: " + (cfg().s3PathStyle ? "ON (R2, B2, MinIO)" : "OFF (AWS S3)"), l, 152, 310, () -> { cfg().s3PathStyle = !cfg().s3PathStyle; commit(); this.clearWidgets(); this.init(); });
		btn("Save and test connection", l, 176, 310, () -> {
			commit();
			note = "Testing...";
			background("s3-test", () -> {
				var s = ClientBridge.s3.testConnection();
				note = s.connected() ? "Connected" : s.message();
				if (s.connected()) { ClientBridge.selectProvider("s3"); ClientBridge.toast("CloudVault", "S3 storage connected and selected"); }
			});
		});
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	private void commit() {
		if (!newSecret.isBlank()) { ClientBridge.setS3Secret(newSecret); newSecret = ""; }
		ClientBridge.core.saveConfig();
	}

	@Override public void onClose() { commit(); super.onClose(); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int l = this.width / 2 - 155;
		left(g, "Endpoint (e.g. https://<account>.r2.cloudflarestorage.com)", l, 40, GREY);
		left(g, "Region (R2: auto)", l, 78, GREY);
		left(g, "Bucket name", l + 160, 78, GREY);
		left(g, "Access key ID", l, 116, GREY);
		left(g, ClientBridge.s3Secret().isEmpty() ? "Secret key" : "Secret key (saved - type to replace)", l + 160, 116, GREY);
		left(g, "Make a private bucket and a key limited to it. Backups go to CloudVault/<world>/.", l, 204, GREY);
		if (!note.isEmpty()) centered(g, note, this.height - 42, "Connected".equals(note) ? GREEN : YELLOW);
	}
}
