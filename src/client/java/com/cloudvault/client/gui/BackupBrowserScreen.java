package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupFilter;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupType;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** World selection, category filter, sorting, and per-backup actions (Details / Restore / Download / Delete). */
public class BackupBrowserScreen extends BaseScreen {
	private static final int PAGE = 5;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm");

	private List<String[]> worlds = new ArrayList<>(); // [id, name]
	private int worldIdx = 0, pageIdx = 0;
	private BackupFilter.Filter filter = BackupFilter.Filter.ALL;
	private BackupFilter.Sort sort = BackupFilter.Sort.NEWEST;
	private List<BackupMetadata> rows = List.of();

	public BackupBrowserScreen(Screen parent) { this(parent, null); }

	/** @param worldId world to show first (null = the open world, or the first one) */
	public BackupBrowserScreen(Screen parent, String worldId) {
		super("Backups", parent);
		loadWorlds();
		if (worldId != null) for (int i = 0; i < worlds.size(); i++) if (worlds.get(i)[0].equals(worldId)) worldIdx = i;
	}

	private void loadWorlds() {
		Map<String, String> m = new LinkedHashMap<>();
		for (BackupMetadata b : ClientBridge.core.store.all()) m.putIfAbsent(b.worldId, b.worldName);
		m.forEach((id, n) -> worlds.add(new String[]{id, n}));
		String cur = ClientBridge.core.currentWorldId();
		for (int i = 0; i < worlds.size(); i++) if (worlds.get(i)[0].equals(cur)) worldIdx = i;
	}

	private void reload() {
		if (worlds.isEmpty()) { rows = List.of(); return; }
		rows = BackupFilter.apply(ClientBridge.core.store.forWorld(worlds.get(worldIdx)[0]), filter, sort);
		pageIdx = Math.max(0, Math.min(pageIdx, (Math.max(1, rows.size()) - 1) / PAGE));
	}

	@Override protected void init() {
		header();
		reload();
		int cx = this.width / 2;
		btn(worlds.isEmpty() ? "No backups yet" : "World: " + worlds.get(worldIdx)[1], cx - 205, 22, 160, () -> { if (!worlds.isEmpty()) { worldIdx = (worldIdx + 1) % worlds.size(); pageIdx = 0; rebuild(); } });
		btn("Show: " + filter.label, cx - 40, 22, 120, () -> { filter = filter.next(); pageIdx = 0; rebuild(); });
		btn("Sort: " + sort.label, cx + 85, 22, 120, () -> { sort = sort.next(); rebuild(); });
		int y = 52;
		for (int i = pageIdx * PAGE; i < Math.min(rows.size(), (pageIdx + 1) * PAGE); i++) {
			final BackupMetadata b = rows.get(i);
			btn("Info", cx + 5, y + 4, 46, () -> this.minecraft.gui.setScreen(new BackupDetailsScreen(this, b)));
			btn("Restore", cx + 53, y + 4, 56, () -> this.minecraft.gui.setScreen(new RestoreScreen(this, b)));
			btn("Export", cx + 111, y + 4, 62, () -> download(b), "Saves a normal ZIP of this backup into .minecraft/cloudvault/exports (downloaded and decrypted first if needed).");
			btn("Delete", cx + 175, y + 4, 46, () -> this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Delete backup?",
				b.pinned ? List.of("This backup is PINNED.", "Permanently delete it from this computer and the cloud?", date(b) + "  " + b.backupType.label()) : List.of("Permanently delete this backup from this computer and the cloud?", date(b) + "  " + b.backupType.label()), "Delete", false, () -> delete(b))));
			y += 32;
		}
		btn("< Prev", cx - 205, this.height - 26, 70, () -> { pageIdx = Math.max(0, pageIdx - 1); rebuild(); });
		btn("Next >", cx - 130, this.height - 26, 70, () -> { pageIdx++; rebuild(); });
		var reset = btn("Reset world...", cx - 55, this.height - 26, 110, () -> ResetFlow.start(this, worlds.get(worldIdx)[0], worlds.get(worldIdx)[1]));
		reset.active = !worlds.isEmpty() && !ClientBridge.core.inWorld() && ResetFlow.folderExists(worlds.get(worldIdx)[0]);
		btn("Done", cx + 135, this.height - 26, 70, this::onClose);
	}

	private void rebuild() { this.clearWidgets(); this.init(); }

	private void download(BackupMetadata b) {
		background("download", () -> {
			Path out = ClientBridge.rootDir().resolve("exports").resolve(b.fileName()); // same readable name as the backup
			ClientBridge.core.cloud.exportZip(b, out);
			ClientBridge.toast("CloudVault", "Saved to " + ClientBridge.rootDir().getParent().relativize(out));
		});
	}

	private void delete(BackupMetadata b) {
		background("delete", () -> { ClientBridge.core.cloud.deleteBackup(b); ClientBridge.toast("CloudVault", "Backup deleted"); });
	}

	static String date(long millis) { return DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())); }
	static String date(BackupMetadata b) { return DATE.format(Instant.ofEpochMilli(b.timestamp).atZone(ZoneId.systemDefault())); }
	static String size(long bytes) {
		if (bytes < 1024) return bytes + " B";
		double v = bytes / 1024.0; String[] u = {"KB", "MB", "GB", "TB"}; int i = 0;
		while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
		return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
	}
	static String cloud(BackupMetadata b) {
		return switch (b.uploadStatus) { case UPLOADED_VERIFIED -> "Uploaded"; case PENDING -> "Pending"; case UPLOADING -> "Uploading"; case FAILED -> "Failed"; case LOCAL_ONLY -> "Local only"; };
	}
	static String integrity(BackupMetadata b) { return b.localVerified ? "Verified" : "Unverified"; }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, y = 52;
		if (rows.isEmpty()) {
			centered(g, filter == BackupFilter.Filter.ALL ? "No backups yet." : "No backups match this filter.", 80, GREY);
			if (filter == BackupFilter.Filter.ALL) { centered(g, "Using this cloud on another computer? Import its backups", 96, GREY); centered(g, "from Settings > Cloud > Manage cloud > Import.", 107, GREY); }
		}
		for (int i = pageIdx * PAGE; i < Math.min(rows.size(), (pageIdx + 1) * PAGE); i++) {
			BackupMetadata b = rows.get(i);
			left(g, date(b) + "  " + b.backupType.label() + "  " + size(b.fileSize), cx - 205, y + 2, WHITE);
			int color = switch (b.uploadStatus) { case UPLOADED_VERIFIED -> GREEN; case FAILED -> RED; default -> YELLOW; };
			left(g, cloud(b) + (b.encrypted ? " (enc)" : "") + " - " + integrity(b) + (b.pinned ? " - PINNED" : "") + (b.testedAt > 0 ? ("OK".equals(b.testResult) ? " - tested OK" : b.testResult.startsWith("FAILED") ? " - TEST FAILED" : "") : ""), cx - 205, y + 14, color);
			y += 32;
		}
		centered(g, "Page " + (pageIdx + 1) + " of " + Math.max(1, (rows.size() + PAGE - 1) / PAGE), this.height - 20, GREY);
	}
}
