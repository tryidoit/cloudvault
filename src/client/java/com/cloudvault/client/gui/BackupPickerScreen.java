package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;
import java.util.function.Consumer;

/** Pick one backup: first the world (if several), then the backup. Used by Compare and Share. */
public class BackupPickerScreen extends BaseScreen {
	private static final int PAGE = 8;
	private final Consumer<BackupMetadata> picked;
	private final List<String[]> worlds = new ArrayList<>();
	private int worldIdx = 0, pageIdx = 0;
	private List<BackupMetadata> rows = List.of();

	public BackupPickerScreen(Screen parent, String title, Consumer<BackupMetadata> picked) {
		super(title, parent); this.picked = picked;
		Map<String, String> m = new LinkedHashMap<>();
		for (BackupMetadata b : ClientBridge.core.store.all()) m.putIfAbsent(b.worldId, b.worldName);
		m.forEach((id, n) -> worlds.add(new String[]{id, n}));
		String cur = ClientBridge.core.currentWorldId();
		for (int i = 0; i < worlds.size(); i++) if (worlds.get(i)[0].equals(cur)) worldIdx = i;
	}

	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		rows = worlds.isEmpty() ? List.of() : ClientBridge.core.store.forWorld(worlds.get(worldIdx)[0]);
		pageIdx = Math.max(0, Math.min(pageIdx, (Math.max(1, rows.size()) - 1) / PAGE));
		btn(worlds.isEmpty() ? "No backups yet" : "World: " + worlds.get(worldIdx)[1] + " (click to change)", cx - 150, 22, 300, () -> { if (!worlds.isEmpty()) { worldIdx = (worldIdx + 1) % worlds.size(); pageIdx = 0; rebuild(); } });
		int y = 48;
		for (int i = pageIdx * PAGE; i < Math.min(rows.size(), (pageIdx + 1) * PAGE); i++) {
			final BackupMetadata b = rows.get(i);
			btn(Fmt.clip(Fmt.describe(b) + (b.comment == null || b.comment.isBlank() ? "" : "  - " + b.comment), 52), cx - 150, y, 300, () -> { this.minecraft.gui.setScreen(parent); picked.accept(b); });
			y += 22;
		}
		btn("< Prev", cx - 150, this.height - 26, 70, () -> { pageIdx = Math.max(0, pageIdx - 1); rebuild(); });
		btn("Next >", cx - 76, this.height - 26, 70, () -> { pageIdx++; rebuild(); });
		btn("Cancel", cx + 80, this.height - 26, 70, () -> this.minecraft.gui.setScreen(parent));
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) { if (rows.isEmpty()) centered(g, "Nothing to pick yet.", 60, GREY); }
}
