package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** "Reset world to latest backup": confirmation, then the engine does verify -> BEFORE RESET backup -> swap, off the game thread. */
final class ResetFlow {
	private ResetFlow() {}

	static Path worldDir(String worldId) {
		String folder = ClientBridge.core.worlds.folderFor(worldId);
		return folder == null ? null : ClientBridge.savesDir().resolve(folder);
	}

	static boolean folderExists(String worldId) {
		Path d = worldDir(worldId);
		return d != null && Files.isDirectory(d);
	}

	static void start(Screen back, String worldId, String worldName) {
		CloudVaultCore core = ClientBridge.core;
		if (core.inWorld()) { ClientBridge.toast("CloudVault", "Leave the world first (go to the title screen) to reset it"); return; }
		Path dir = worldDir(worldId);
		if (dir == null || !Files.isDirectory(dir)) { ClientBridge.toast("CloudVault", "World folder not found on this computer"); return; }
		BackupMetadata latest = core.restore.latestUsable(worldId);
		if (latest == null) { ClientBridge.toast("CloudVault", "This world has no backup to reset to yet"); return; }
		ClientBridge.open(new CvConfirmScreen(back, "RESET WORLD?", List.of(
			"Reset \"" + worldName + "\" to its latest backup:",
			BackupBrowserScreen.date(latest) + "  (" + latest.backupType.label() + ")",
			"Everything played since then will be undone.",
			"First, a separate backup labeled BEFORE RESET is made of the world as it is now.",
			"The old world folder is also kept in cloudvault/replaced."), "Reset world", true, () -> run(worldId, dir, worldName)));
	}

	private static void run(String worldId, Path dir, String name) {
		Thread t = new Thread(() -> {
			try {
				ClientBridge.toast("CloudVault", "Backing up the current world, then resetting...");
				ClientBridge.core.queue.awaitIdle(60_000); // never swap the folder while another backup is still reading it
				var r = ClientBridge.core.restore.resetToLatest(worldId, dir, name, ClientBridge.minecraftVersion());
				ClientBridge.toast("CloudVault", "World reset to the backup from " + BackupBrowserScreen.date(r.restoredFrom()) + ". Old state saved as BEFORE RESET.");
			} catch (Throwable e) {
				String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				ClientBridge.toast("CloudVault", m.length() > 100 ? m.substring(0, 100) : m);
			}
		}, "CloudVault-reset");
		t.setDaemon(true);
		t.start();
	}
}
