package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupType;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.CloudImporter;
import com.cloudvault.cloud.StorageManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Import a world from the cloud. Step 1: the worlds found in the cloud (Import = its newest backup, Open = choose a backup).
 * Step 2: that world's backups. Import puts the world into your saves folder as a NEW world. Nothing else happens:
 * no backup list entries, no settings, no uploads.
 */
public class ImportScreen extends BaseScreen {
	private static final int PAGE = 5;

	private volatile CloudImporter.Snapshot snapshot;
	private volatile String status = "Looking in the cloud...";
	private volatile int statusColor = YELLOW;
	private volatile boolean busy = true;
	private List<CloudImporter.CloudWorld> worlds = List.of();
	private List<CloudImporter.CloudEntry> entries = List.of();
	private String openFolder = null, openName = "";
	private int pageIdx = 0;
	private volatile boolean rebuildNext = false;

	public ImportScreen(Screen parent) { super("Import World from Cloud", parent); fetch(); }

	private CloudVaultCore core() { return ClientBridge.core; }

	private void say(String s, int color) { status = s; statusColor = color; }

	private void fetch() {
		busy = true; say("Looking in the cloud...", YELLOW);
		background("import-fetch", () -> {
			try {
				snapshot = core().importer.fetch();
				worlds = core().importer.worlds(snapshot);
				if (openFolder != null) entries = core().importer.entries(snapshot, openFolder);
				say(worlds.isEmpty() ? "No CloudVault backups found in " + ClientBridge.providerName() + "." : "", GREY);
			} catch (Exception e) { say(e.getMessage() == null ? "Could not read the cloud" : e.getMessage(), RED); }
			finally { busy = false; rebuildNext = true; }
		});
	}

	@Override public void tick() { if (rebuildNext) { rebuildNext = false; rebuild(); } }
	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		btn("From share code...", cx + 105, 20, 100, () -> this.minecraft.gui.setScreen(new ShareImportScreen(this)), "Paste a code or link someone sent you.");
		if (openFolder == null) {
			for (int i = pageIdx * PAGE; i < Math.min(worlds.size(), (pageIdx + 1) * PAGE); i++) {
				final var w = worlds.get(i);
				var open = btn("Open", cx + 70, y + 6, 60, () -> { openFolder = w.folder(); openName = w.worldName(); entries = core().importer.entries(snapshot, w.folder()); pageIdx = 0; rebuild(); },
					"Choose which backup of this world to import.");
				open.active = !busy;
				var imp = btn("Import", cx + 134, y + 6, 70, () -> importEntry(w.worldName(), core().importer.entries(snapshot, w.folder()).stream().findFirst().orElse(null)),
					"Adds this world's newest backup to your worlds as a new world.");
				imp.active = !busy;
				y += 36;
			}
		} else {
			for (int i = pageIdx * PAGE; i < Math.min(entries.size(), (pageIdx + 1) * PAGE); i++) {
				final var e = entries.get(i);
				var b = btn("Import", cx + 134, y + 6, 70, () -> importEntry(openName, e), "Adds this backup to your worlds as a new world.");
				b.active = !busy;
				y += 36;
			}
		}
		btn("< Prev", cx - 205, this.height - 26, 60, () -> { pageIdx = Math.max(0, pageIdx - 1); rebuild(); });
		btn("Next >", cx - 141, this.height - 26, 60, () -> { int n = openFolder == null ? worlds.size() : entries.size(); pageIdx = Math.min(pageIdx + 1, Math.max(0, (n - 1) / PAGE)); rebuild(); });
		if (openFolder == null) btn("Refresh", cx - 75, this.height - 26, 70, this::fetch).active = !busy;
		else btn("Worlds", cx - 75, this.height - 26, 70, () -> { openFolder = null; pageIdx = 0; rebuild(); });
		btn("Done", cx + 135, this.height - 26, 70, this::onClose);
	}

	/** Downloads the backup and adds it to the saves folder as a new world. That is all. */
	private void importEntry(String worldName, CloudImporter.CloudEntry e) {
		if (busy || e == null) return;
		busy = true; say("Importing... (downloading and unpacking)", YELLOW);
		rebuild();
		var p = e.parsed();
		String folder = (worldName == null || worldName.isBlank() ? "Imported world" : worldName) + (p.date().isEmpty() ? "" : " (" + p.date() + " " + p.time() + ")");
		background("import-run", () -> {
			try {
				var dir = core().importWorldFromCloud(e.file(), folder, ClientBridge.savesDir());
				String shown = dir.getFileName().toString();
				ClientBridge.toast("CloudVault", "Imported \"" + shown + "\"");
				say("Imported as \"" + shown + "\". Go back to the world list to play it.", GREEN);
			} catch (Exception ex) { say(ex.getMessage() == null ? "Import failed" : ex.getMessage(), RED); }
			finally { busy = false; rebuildNext = true; }
		});
	}

	private static int typeColor(BackupType t) {
		if (t == null) return GREY;
		return switch (t) { case MANUAL -> GREEN; case SAFETY, PRE_RESET -> YELLOW; default -> WHITE; };
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, y = 44;
		if (openFolder == null) {
			centered(g, "Worlds found in " + ClientBridge.providerName(), 24, GREY);
			for (int i = pageIdx * PAGE; i < Math.min(worlds.size(), (pageIdx + 1) * PAGE); i++) {
				var w = worlds.get(i);
				String nm = w.worldName().length() > 30 ? w.worldName().substring(0, 29) + "..." : w.worldName();
				left(g, nm, cx - 205, y + 4, WHITE);
				left(g, w.count() + " backups (" + StorageManager.human(w.bytes()) + ")" + (w.newest().isEmpty() ? "" : "  last " + w.newest()), cx - 205, y + 16, GREY);
				y += 36;
			}
		} else {
			centered(g, "\"" + openName + "\" - backups in the cloud", 24, GREY);
			for (int i = pageIdx * PAGE; i < Math.min(entries.size(), (pageIdx + 1) * PAGE); i++) {
				var e = entries.get(i); var p = e.parsed();
				left(g, p.date().isEmpty() ? "(date unknown)" : p.when(), cx - 205, y + 4, WHITE);
				left(g, p.type() == null ? "BACKUP" : p.type().label(), cx - 40, y + 4, typeColor(p.type()));
				left(g, StorageManager.human(e.file().size()) + (p.encrypted() ? "  encrypted" : ""), cx - 205, y + 16, GREY);
				y += 36;
			}
		}
		if (!status.isEmpty()) centered(g, status, this.height - 44, statusColor);
		else if (!worlds.isEmpty()) centered(g, "Import adds the world to your saves as a new world. Nothing else changes.", this.height - 44, GREY);
	}
}
