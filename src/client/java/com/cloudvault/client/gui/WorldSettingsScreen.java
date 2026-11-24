package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.config.WorldSettings;
import com.google.gson.Gson;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** Settings of ONE world: on/off, its own backup settings (opens the Backups pages for that world), cloud sync. */
public class WorldSettingsScreen extends BaseScreen {
	private final String id, name;

	public WorldSettingsScreen(Screen parent, String worldId, String worldName) { super("World: " + worldName, parent); this.id = worldId; this.name = worldName; }

	@Override protected boolean wizardButton() { return true; }
	private CloudVaultConfig cfg() { return ClientBridge.core.config; }
	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		var core = ClientBridge.core;
		WorldSettings w = cfg().worldSettings(id);
		boolean off = cfg().isWorldDisabled(id);
		btn((off ? "[ ]" : "[x]") + " CloudVault enabled for this world", cx - 140, y, 280, () -> { core.setWorldEnabled(id, name, off); rebuild(); }, "Untick to turn CloudVault off for this world: no backups and no uploads. Backups it already has stay available."); y += 24;
		btn("Own backup settings for this world: " + (w.useOverride ? "ON" : "OFF"), cx - 140, y, 280, () -> {
			if (!w.useOverride) { Gson g = new Gson(); w.settings = g.fromJson(g.toJson(cfg().global), BackupSettings.class); }
			w.useOverride = !w.useOverride; w.lastKnownName = name; ClientBridge.settingsChanged(); rebuild();
		}, "ON: the pages below change only this world. OFF: this world follows the defaults for all worlds."); y += 24;
		btn("Cloud sync for this world: " + (cfg().isWorldSynced(id) ? "ON" : "OFF"), cx - 140, y, 280, () -> {
			boolean cur = w.syncEnabled != null ? w.syncEnabled : cfg().sync.newWorldsSync;
			w.syncEnabled = !cur; w.lastKnownName = name; ClientBridge.settingsChanged(); rebuild();
		}, "ON: saved to the cloud sync folder when you leave it and brought to your other computers automatically."); y += 30;
		for (String sub : new String[]{"When", "Keeping", "Comments"}) {
			var b = btn("Backups: " + sub + "...", cx - 140, y, 280, () -> this.minecraft.gui.setScreen(new PageScreen(this, PageTree.REG.page("Backups/" + sub), id, name)));
			b.active = w.useOverride; y += 24;
		}
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		WorldSettings w = cfg().worlds.get(id);
		centered(g, cfg().isWorldDisabled(id) ? "CloudVault is OFF for \"" + name + "\": no backups or uploads." : w != null && w.useOverride ? "The pages below change \"" + name + "\" only." : "This world follows the defaults for all worlds (switch on own settings to edit them).", 26, GREY);
	}
}
