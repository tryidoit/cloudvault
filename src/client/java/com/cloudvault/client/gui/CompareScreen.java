package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.compare.BackupComparer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/** Pick two backups and see what changed between them (files, sizes, groups, level info). Works on incremental and cloud-only backups. */
public class CompareScreen extends BaseScreen {
	private BackupMetadata a, b;
	private volatile BackupComparer.Result result;
	private volatile String note = "";

	public CompareScreen(Screen parent) { super("Compare two backups", parent); }

	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		btn("Older (A): " + (a == null ? "choose..." : Fmt.clip(Fmt.describe(a), 40)), cx - 150, 24, 300, () -> this.minecraft.gui.setScreen(new BackupPickerScreen(this, "Pick backup A", x -> { a = x; result = null; })), "The backup to compare from.");
		btn("Newer (B): " + (b == null ? "choose..." : Fmt.clip(Fmt.describe(b), 40)), cx - 150, 48, 300, () -> this.minecraft.gui.setScreen(new BackupPickerScreen(this, "Pick backup B", x -> { b = x; result = null; })), "The backup to compare to.");
		var go = btn("Compare", cx - 150, 72, 146, () -> {
			note = "Comparing..."; result = null;
			background("compare", () -> { try { result = BackupComparer.compare(ClientBridge.core.store, ClientBridge.core.cloud, a, b, true); note = ""; } catch (Exception e) { note = "Could not compare: " + Fmt.clip(e.getMessage(), 60); } });
		});
		go.active = a != null && b != null;
		btn("Swap A and B", cx + 4, 72, 146, () -> { var t = a; a = b; b = t; result = null; rebuild(); }).active = a != null && b != null;
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int x = this.width / 2 - 150, y = 100;
		if (!note.isEmpty()) { left(g, note, x, y, YELLOW); return; }
		BackupComparer.Result r = result;
		if (r == null) { left(g, "Choose two backups of the same world, then Compare.", x, y, GREY); return; }
		List<String> lines = new ArrayList<>();
		lines.add("Added " + r.added().size() + "   Removed " + r.removed().size() + "   Changed " + r.changed().size() + "   Same " + r.unchanged());
		lines.add("Size of the world: " + Fmt.size(r.bytesA()) + "  ->  " + Fmt.size(r.bytesB()));
		for (var gr : r.groups()) if (gr.added() + gr.removed() + gr.changed() > 0) lines.add(gr.name() + ": +" + gr.added() + " -" + gr.removed() + " ~" + gr.changed());
		for (String[] i : r.info()) lines.add(i[0] + ": " + i[1]);
		for (var c : r.changed()) lines.add("~ " + c.path());
		for (var c : r.added()) lines.add("+ " + c.path());
		for (var c : r.removed()) lines.add("- " + c.path());
		int max = (this.height - 34 - y) / 11;
		for (int i = 0; i < Math.min(lines.size(), max); i++) { left(g, Fmt.clip(lines.get(i), 60), x, y, i == 0 ? WHITE : GREY); y += 11; }
		if (lines.size() > max) left(g, "... and " + (lines.size() - max) + " more", x, y, GREY);
	}
}
