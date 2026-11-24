package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.share.ShareManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;

/** Import a world from a share code or link someone sent you. The download is checked (size, hash, zip) and added as a NEW world. */
public class ShareImportScreen extends BaseScreen {
	private EditBox box;
	private String text = "";
	private volatile String note = "";
	private volatile boolean busy = false;

	public ShareImportScreen(Screen parent) { super("From share code", parent); }

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		box = new EditBox(this.font, cx - 150, 52, 300, 20, Component.literal("Share code"));
		box.setMaxLength(8000); box.setValue(text); box.setResponder(v -> text = v.trim());
		this.addRenderableWidget(box);
		btn("Paste", cx - 150, 78, 70, () -> { text = ClientBridge.pasteboard(); box.setValue(text); }, "Pastes what you copied.");
		var go = btn("Download and import", cx - 76, 78, 226, this::run, "Downloads the shared world, checks it, and adds it to your worlds as a new world.");
		go.active = !busy;
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	private void run() {
		final String t = text;
		busy = true; note = "Reading the code...";
		background("share-import", () -> {
			try {
				ShareManager.Parsed p = ShareManager.parse(t);
				note = "Downloading \"" + p.worldName() + "\"...";
				var zip = ShareManager.download(p, ClientBridge.core.store.temp);
				try {
					var dir = ClientBridge.core.restore.extractAsNewWorld(zip, p.worldName(), ClientBridge.savesDir());
					note = "Imported as \"" + dir.getFileName() + "\". Go back to the world list to play it.";
					ClientBridge.toast("CloudVault", "Imported \"" + dir.getFileName() + "\"");
				} finally { Files.deleteIfExists(zip); }
			} catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 60); }
			finally { busy = false; }
		});
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		centered(g, "Paste the share code or link (it starts with cvshare1: or https://)", 38, GREY);
		if (!note.isEmpty()) centered(g, note, 112, note.startsWith("Failed") ? RED : note.startsWith("Imported") ? GREEN : YELLOW);
		centered(g, "Only import worlds from people you trust.", 130, GREY);
	}
}
