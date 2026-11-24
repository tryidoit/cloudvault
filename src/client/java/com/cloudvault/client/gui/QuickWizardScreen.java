package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.cloud.google.BuiltInOAuth;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.CloudVaultConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Quick setup, one straight line with Back/Next: connect cloud > settings from another computer > what to back up and when (incl. smart
 * triggers) > incremental > sync > alerts > done. It counts as done the moment it opens, so it never comes back by itself.
 */
public class QuickWizardScreen extends BaseScreen {
	private static final int LAST = 7;
	private int step = 0;
	private volatile String note = "";
	private volatile int found = -1;       // settings files found in the cloud (-1 = not looked yet)
	private volatile boolean looking = false, rebuildNext = false;

	public QuickWizardScreen(Screen parent) {
		super("Quick setup", parent);
		if (!cfg().firstLaunchDone) { cfg().firstLaunchDone = true; ClientBridge.settingsChanged(); }
	}

	@Override protected boolean showCrumbs() { return false; }
	private CloudVaultConfig cfg() { return ClientBridge.core.config; }
	private BackupSettings s() { return cfg().global; }
	private void rebuild() { this.clearWidgets(); this.init(); }
	@Override public void tick() { if (rebuildNext) { rebuildNext = false; rebuild(); } }
	private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

	private void toggle(int y, String label, boolean value, Runnable flip, String tip) {
		btn(label + ": " + onOff(value), this.width / 2 - 130, y, 260, () -> { flip.run(); ClientBridge.settingsChanged(); rebuild(); }, tip);
	}

	private void lookForSettings() {
		if (looking || found >= 0 || !ClientBridge.provider().isAuthenticated()) return;
		looking = true;
		background("wizard-account", () -> {
			try { found = (int) ClientBridge.core.account.listProfiles().stream().filter(p -> !p.mine()).count(); } catch (Exception e) { found = 0; }
			finally { looking = false; rebuildNext = true; }
		});
	}

	@Override protected void init() {
		int cx = this.width / 2, x = cx - 130;
		switch (step) {
			case 1 -> {
				boolean s3 = "s3".equals(cfg().cloudProvider);
				btn("Cloud: " + ClientBridge.providerName() + " (click to change)", x, 48, 260, () -> { ClientBridge.selectProvider(s3 ? "google_drive" : "s3"); found = -1; note = ""; rebuild(); });
				if (s3) btn("Enter storage details...", x, 72, 260, () -> this.minecraft.gui.setScreen(new S3SettingsScreen(this)));
				else btn("Connect Google Drive (opens your browser)", x, 72, 260, () -> { note = "Finish signing in in your browser..."; background("wizard-auth", () -> { ClientBridge.drive.authenticate(); ClientBridge.core.cloud.resumePending(); note = "Connected"; found = -1; ClientBridge.toast("CloudVault", "Google Drive connected"); rebuildNext = true; }); }).active = BuiltInOAuth.available();
				toggle(98, "Upload backups to the cloud", cfg().cloudEnabled, () -> cfg().cloudEnabled = !cfg().cloudEnabled, "Off keeps backups on this computer only.");
			}
			case 2 -> {
				lookForSettings();
				boolean ok = ClientBridge.provider().isAuthenticated();
				var use = btn(found > 0 ? "Choose which settings file to use (" + found + " found)" : "Look at settings saved in my cloud...", x, 62, 260, () -> this.minecraft.gui.setScreen(new AccountScreen(this)), "Copies the settings another computer saved: backups, second destination, alerts, sync. Your cloud login stays as it is.");
				use.active = ok;
				toggle(88, "Keep my settings in my cloud (account)", cfg().account.enabled, () -> {
					if (!cfg().account.enabled) background("wizard-acc", () -> { try { ClientBridge.core.account.enable(); note = "Settings file created in your cloud"; } catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage(), 50); } rebuildNext = true; });
					else ClientBridge.core.account.disable(false);
				}, "A new computer can then set itself up from this one. Updated whenever you change a setting.");
			}
			case 3 -> {
				toggle(44, "Back up when I join a world", s().session.joinEnabled, () -> s().session.joinEnabled = !s().session.joinEnabled, null);
				toggle(68, "Back up when I leave a world", s().session.leaveEnabled, () -> s().session.leaveEnabled = !s().session.leaveEnabled, null);
				toggle(92, "Periodic (every " + s().periodic.amount + " " + s().periodic.unit.label + ")", s().periodic.enabled, () -> s().periodic.enabled = !s().periodic.enabled, null);
				toggle(116, "After " + s().gameplay.amount + " " + s().gameplay.unit.label + " of play", s().gameplay.enabled, () -> s().gameplay.enabled = !s().gameplay.enabled, null);
				toggle(140, "Smart triggers (Nether, End, low health)", s().smart.enabled, () -> s().smart.enabled = !s().smart.enabled, "Extra backups at moments that matter, at most one every " + s().smart.cooldownMinutes + " minutes.");
				toggle(164, "New worlds start with CloudVault", cfg().newWorldsEnabled, () -> ClientBridge.core.setNewWorldsEnabled(!cfg().newWorldsEnabled), null);
			}
			case 4 -> toggle(70, "Incremental backups", s().incremental.enabled, () -> s().incremental.enabled = !s().incremental.enabled, "Later backups store only what changed. It never uses more space than full backups and falls back to a full backup whenever in doubt.");
			case 5 -> {
				toggle(60, "Sync new worlds between my computers", cfg().sync.newWorldsSync, () -> cfg().sync.newWorldsSync = !cfg().sync.newWorldsSync, "Worlds are saved to your cloud when you leave them and brought to your other computers.");
				toggle(86, "Warn when a world is open elsewhere", cfg().lock.enabled, () -> cfg().lock.enabled = !cfg().lock.enabled, "Stops two computers from changing one world at once.");
			}
			case 6 -> {
				toggle(60, "Send alerts to my phone / chat / e-mail", cfg().alerts.enabled, () -> cfg().alerts.enabled = !cfg().alerts.enabled, "Only when something goes wrong.");
				btn("Add or edit alert channels...", x, 86, 260, () -> this.minecraft.gui.setScreen(new AlertChannelsScreen(this)));
			}
			case LAST -> btn("Open Main Settings", cx - 100, 140, 200, () -> { finish(); this.minecraft.gui.setScreen(new CloudVaultScreen(parent)); });
			default -> { }
		}
		btn("< Back", cx - 130, this.height - 26, 70, () -> { step--; note = ""; rebuild(); }).active = step > 0;
		btn(step == LAST ? "Finish" : "Next >", cx - 50, this.height - 26, 100, () -> { if (step == LAST) onClose(); else { step++; note = ""; rebuild(); } });
		if (step < LAST) btn("Skip setup", cx + 60, this.height - 26, 80, this::onClose);
	}

	private void finish() { ClientBridge.settingsChanged(); }
	@Override public void onClose() { finish(); this.minecraft.gui.setScreen(parent); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		centered(g, "Quick setup - step " + (step + 1) + " of " + (LAST + 1), 10, GREY);
		switch (step) {
			case 0 -> {
				centered(g, "CloudVault makes safe copies of your Minecraft worlds", 40, WHITE);
				centered(g, "and keeps them in your own cloud storage.", 52, WHITE);
				centered(g, "Every backup is checked after it is made and again after", 74, GREY);
				centered(g, "it is uploaded. Setup takes about a minute.", 86, GREY);
				centered(g, "Want to answer every question? Use Advanced setup", 108, GREY);
				centered(g, "(the Setup wizard button on any settings page).", 120, GREY);
			}
			case 1 -> {
				boolean ok = ClientBridge.provider().isAuthenticated();
				centered(g, ok ? "Status: connected" : "Status: not connected yet", 34, ok ? GREEN : YELLOW);
				centered(g, !note.isEmpty() ? Fmt.clip(note, 70) : "You can also connect later: Main Settings > Cloud.", 128, GREY);
			}
			case 2 -> {
				centered(g, "Used CloudVault on another computer?", 34, GREY);
				if (!ClientBridge.provider().isAuthenticated()) centered(g, "Connect your cloud on the previous step first.", 116, YELLOW);
				else if (looking) centered(g, "Looking for settings in your cloud...", 116, GREY);
				else if (found == 0) centered(g, "No settings from another computer found.", 116, GREY);
				else if (found > 0) centered(g, found + " settings file(s) from other computers found.", 116, GREEN);
				if (!note.isEmpty()) centered(g, Fmt.clip(note, 70), 130, note.startsWith("Failed") ? RED : GREEN);
			}
			case 3 -> centered(g, "Choose when CloudVault makes backups:", 30, GREY);
			case 4 -> { centered(g, "Incremental backups store only what changed since the last one.", 40, GREY); centered(g, "Smaller and faster; restoring always gives the complete world.", 52, GREY); }
			case 5 -> { centered(g, "Sync keeps the same world up to date on all your computers.", 34, GREY); centered(g, "You can switch it on per world later.", 46, GREY); }
			case 6 -> centered(g, "Get a message outside the game when a backup fails. Optional.", 36, GREY);
			default -> {
				boolean ok = ClientBridge.provider().isAuthenticated();
				centered(g, "All set!", 36, GREEN);
				centered(g, "Cloud: " + (cfg().cloudEnabled ? ClientBridge.providerName() + (ok ? " (connected)" : " (not connected yet)") : "this computer only"), 58, ok || !cfg().cloudEnabled ? WHITE : YELLOW);
				centered(g, "Backups: " + (s().session.joinEnabled ? "join " : "") + (s().session.leaveEnabled ? "leave " : "") + (s().periodic.enabled ? "periodic " : "") + (s().gameplay.enabled ? "play-time " : "") + (s().smart.enabled ? "smart " : "") + "(manual always available)", 72, WHITE);
				centered(g, "Incremental: " + onOff(s().incremental.enabled) + "   Sync: " + onOff(cfg().sync.newWorldsSync) + "   Alerts: " + onOff(cfg().alerts.enabled), 86, WHITE);
				centered(g, "Open CloudVault any time: pause menu button or the CloudVault key.", 108, GREY);
			}
		}
	}
}
