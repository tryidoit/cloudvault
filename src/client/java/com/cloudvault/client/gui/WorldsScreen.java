package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupType;
import com.cloudvault.backup.BackupManager;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Every world on this computer with quick actions: back up now, browse backups, reset to the latest backup. Works from the title screen. */
public class WorldsScreen extends BaseScreen {
	private static final int PAGE = 4;

	private record Row(String folder, Path dir, String id, String name, int count, long last, boolean disabled) {}
	private List<Row> rows = new ArrayList<>();
	private int pageIdx = 0;

	public WorldsScreen(Screen parent) { super("Worlds and Backups", parent); load(); }

	private CloudVaultCore core() { return ClientBridge.core; }

	private void load() {
		List<Row> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(ClientBridge.savesDir())) {
			record Found(Path dir, long modified) {}
			List<Found> found = new ArrayList<>();
			for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p.resolve("level.dat"))) found.add(new Found(p, p.toFile().lastModified())); // read once, not on every comparison
			found.sort(Comparator.comparingLong(Found::modified).reversed());
			for (Found f : found) {
				Path d = f.dir();
				String folder = d.getFileName().toString();
				String id = core().worlds.idIfKnown(folder);
				List<BackupMetadata> bs = id == null ? List.of() : core().store.forWorld(id);
				String name = bs.isEmpty() ? folder : bs.get(0).worldName;
				out.add(new Row(folder, d, id, name, bs.size(), bs.isEmpty() ? 0 : bs.get(0).timestamp, id != null && core().isWorldDisabled(id)));
			}
		} catch (IOException ignored) { /* no saves folder yet */ }
		rows = out;
		pageIdx = Math.max(0, Math.min(pageIdx, (Math.max(1, rows.size()) - 1) / PAGE));
	}

	/** The world's id; worlds that were never backed up get one here so their settings have somewhere to live. */
	private String idOf(Row r) { return r.id() != null ? r.id() : core().worlds.idFor(r.folder()); }

	private boolean isLoaded(Row r) {
		return core().inWorld() && core().currentWorldDir() != null
			&& core().currentWorldDir().toAbsolutePath().normalize().equals(r.dir().toAbsolutePath().normalize());
	}

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 34;
		for (int i = pageIdx * PAGE; i < Math.min(rows.size(), (pageIdx + 1) * PAGE); i++) {
			final Row r = rows.get(i);
			btn(r.disabled() ? "[ ]" : "[x]", cx - 205, y + 6, 24, () -> { core().setWorldEnabled(idOf(r), r.name(), r.disabled()); load(); rebuild(); });
			var bu = btn("Back up", cx - 5, y + 6, 52, () -> backUp(r));
			bu.active = !r.disabled();
			var br = btn("Backups", cx + 49, y + 6, 56, () -> this.minecraft.gui.setScreen(new BackupBrowserScreen(this, r.id())));
			br.active = r.count() > 0;
			var rs = btn("Reset", cx + 107, y + 6, 44, () -> ResetFlow.start(this, r.id(), r.name()));
			rs.active = r.count() > 0 && !core().inWorld();
			btn("Settings", cx + 153, y + 6, 52, () -> this.minecraft.gui.setScreen(new WorldSettingsScreen(this, idOf(r), r.name())));
			y += 38;
		}
		btn("< Prev", cx - 205, this.height - 26, 60, () -> { pageIdx = Math.max(0, pageIdx - 1); rebuild(); });
		btn("Next >", cx - 141, this.height - 26, 60, () -> { pageIdx++; if (pageIdx > (Math.max(1, rows.size()) - 1) / PAGE) pageIdx = (Math.max(1, rows.size()) - 1) / PAGE; rebuild(); });
		btn("Import from cloud", cx - 75, this.height - 26, 120, () -> this.minecraft.gui.setScreen(new ImportScreen(this)));
		btn("Done", cx + 135, this.height - 26, 70, this::onClose);
	}

	private void rebuild() { this.clearWidgets(); this.init(); }

	private void backUp(Row r) {
		if (isLoaded(r)) { // an open world must be saved first: the normal manual backup does that
			if (ClientBridge.saveAndBackup(BackupType.MANUAL)) ClientBridge.toast("CloudVault", "Backup started...");
			return;
		}
		String id = r.id() != null ? r.id() : core().worlds.idFor(r.folder());
		core().backups.submit(new BackupManager.Request(r.dir(), r.name(), id, BackupType.MANUAL, ClientBridge.minecraftVersion()), null);
		ClientBridge.toast("CloudVault", "Backup started...");
		rebuildLater = true;
	}

	private volatile boolean rebuildLater = false;
	private boolean wasBusy = false;

	/** Reloads only when something changed: after a backup was started here, or when running work just finished. No periodic disk scans. */
	@Override public void tick() {
		boolean busy = core().activity.phase() != com.cloudvault.backup.Activity.Phase.IDLE || core().queue.size() > 0;
		if (rebuildLater || (wasBusy && !busy)) { rebuildLater = false; load(); rebuild(); }
		wasBusy = busy || rebuildLater;
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, y = 34;
		if (rows.isEmpty()) { centered(g, "No worlds found on this computer.", 60, GREY); return; }
		for (int i = pageIdx * PAGE; i < Math.min(rows.size(), (pageIdx + 1) * PAGE); i++) {
			Row r = rows.get(i);
			String nm = r.name().length() > 18 ? r.name().substring(0, 17) + "..." : r.name();
			left(g, nm + (isLoaded(r) ? " (open)" : ""), cx - 177, y + 4, r.disabled() ? GREY : WHITE);
			left(g, r.disabled() ? "CloudVault is off" : r.count() == 0 ? "No backups yet" : r.count() + " backups, last " + BackupBrowserScreen.date(r.last()), cx - 177, y + 16, r.disabled() ? RED : r.count() == 0 ? YELLOW : GREY);
			y += 38;
		}
		centered(g, "[x] = CloudVault on for that world, [ ] = off (no backups or uploads)", this.height - 54, GREY);
		if (core().inWorld()) centered(g, "To reset a world, leave it first (go to the title screen).", this.height - 42, YELLOW);
		else centered(g, "Page " + (pageIdx + 1) + " of " + Math.max(1, (rows.size() + PAGE - 1) / PAGE), this.height - 42, GREY);
	}
}
