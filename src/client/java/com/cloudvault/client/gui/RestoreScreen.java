package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupManager;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupType;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Default action = restore as a NEW world. Replacing a world needs a strong confirmation and the world must not be loaded. */
public class RestoreScreen extends BaseScreen {
	private final BackupMetadata b;
	public RestoreScreen(Screen parent, BackupMetadata b) { super("Restore Backup", parent); this.b = b; }

	private BackupManager.Request safetyRequest() {
		CloudVaultCore core = ClientBridge.core;
		if (core.inWorld() && b.worldId.equals(core.currentWorldId())) return core.currentWorldRequest();
		String folder = core.worlds.folderFor(b.worldId);
		if (folder == null) return null;
		Path dir = ClientBridge.savesDir().resolve(folder);
		return Files.isDirectory(dir) ? new BackupManager.Request(dir, b.worldName, b.worldId, BackupType.SAFETY, ClientBridge.minecraftVersion()) : null;
	}

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		btn("Restore as New World (recommended)", cx - 120, 70, 240, () -> {
			BackupManager.Request safety = safetyRequest();
			this.minecraft.gui.setScreen(parent);
			background("restore", () -> {
				ClientBridge.toast("CloudVault", "Verifying and restoring...");
				Path p = ClientBridge.core.restore.restoreAsNew(b, ClientBridge.savesDir(), safety);
				ClientBridge.toast("CloudVault", "Restored as \"" + p.getFileName() + "\"");
			});
		});
		var replace = btn("Replace Current World", cx - 120, 100, 240, () -> {
			BackupManager.Request safety = safetyRequest();
			if (safety == null) { ClientBridge.toast("CloudVault", "World folder not found - use Restore as New World"); return; }
			this.minecraft.gui.setScreen(new CvConfirmScreen(parent, "REPLACE WORLD?", List.of(
				"This replaces \"" + b.worldName + "\" with the backup from " + BackupBrowserScreen.date(b) + ".",
				"A safety backup is made first, and the old folder is kept in cloudvault/replaced.",
				"Anything played since that backup will be rolled back."), "Replace", true, () -> background("replace", () -> {
					ClientBridge.toast("CloudVault", "Replacing world...");
					ClientBridge.core.restore.replaceWorld(b, safety.worldDir(), safety);
					ClientBridge.toast("CloudVault", "World replaced");
				})));
		});
		replace.active = !ClientBridge.core.inWorld(); // never replace a world that is currently open
		btn("Cancel", cx - 50, this.height - 26, 100, () -> this.minecraft.gui.setScreen(parent));
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		centered(g, b.worldName + " - " + BackupBrowserScreen.date(b) + " (" + b.backupType.label() + ")", 30, WHITE);
		centered(g, "The backup is verified first, and your current world is backed up before anything changes.", 44, GREY);
		if (ClientBridge.core.inWorld()) centered(g, "To replace a world, leave it first (go to the title screen).", 126, YELLOW);
	}
}
