package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.backup.BackupType;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.util.TimeUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

public class CloudVaultScreen extends BaseScreen {
	@Override protected boolean wizardButton() { return true; }
	private int uploaded, pending, failed, ticks;
	private String lastBackup = "Never";

	public CloudVaultScreen(Screen parent) { super("Main Settings", parent); }

	/** The top of every path: CloudVault > Main Settings. */
	@Override protected String[] crumbs() { return new String[]{"CloudVault", "Main Settings"}; }

	@Override protected void init() {
		header();
		CloudVaultCore core = ClientBridge.core;
		int cx = this.width / 2;
		Button save = btn("SAVE CURRENT STATE", cx - 100, 24, 200, this::saveNow);
		save.active = core.inWorld() && !core.isWorldDisabled(core.currentWorldId());
		if (core.isAutoPaused()) btn("Resume automatic backups", cx - 100, 48, 200, () -> { core.resumeAuto(); ClientBridge.toast("CloudVault", "Automatic backups resumed"); rebuild(); });
		else {
			btn("Pause 1h", cx - 100, 48, 64, () -> pause(60));
			btn("Pause 4h", cx - 32, 48, 64, () -> pause(240));
			btn("Pause", cx + 36, 48, 64, () -> pause(0));
		}
		if (core.inWorld()) {
			String wid = core.currentWorldId();
			btn((core.isWorldDisabled(wid) ? "[ ]" : "[x]") + " CloudVault enabled for this world", cx - 100, 72, 200, () -> { core.setWorldEnabled(wid, core.currentWorldName(), core.isWorldDisabled(wid)); rebuild(); });
		}
		// every page branches from here: two columns of buttons at the bottom, the status above them
		int by = this.height - 100, i = 0;
		for (String key : PageTree.TOP) {
			int x = (i % 2 == 0) ? cx - 100 : cx + 1;
			btn(key, x, by, 99, () -> this.minecraft.gui.setScreen(PageTree.open(this, key)), PageTree.TIPS.get(key));
			if (i % 2 == 1) by += 24;
			i++;
		}
		if (i % 2 == 1) by += 24;
		btn("Done", cx - 50, by, 100, this::onClose);
		refresh();
	}

	private void pause(long minutes) {
		ClientBridge.core.pauseAuto(minutes);
		ClientBridge.toast("CloudVault", minutes <= 0 ? "Automatic backups paused until you resume them" : "Automatic backups paused for " + (minutes / 60) + "h");
		rebuild();
	}

	private void rebuild() { this.clearWidgets(); this.init(); }

	private void saveNow() {
		CloudVaultCore core = ClientBridge.core;
		Runnable go = () -> { if (ClientBridge.saveAndBackup(BackupType.MANUAL)) ClientBridge.toast("CloudVault", "Backup started..."); };
		if (core.settings().manual.confirmBeforeBackup)
			this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Save current state?", List.of("Create a backup of \"" + core.currentWorldName() + "\" now?"), "Save", false, go));
		else go.run();
	}

	private String testLine = "", spaceLine = null;
	private int testColor = GREY;

	private void refresh() {
		var st = ClientBridge.core.store.stats(); // counts only, no copying of every record
		uploaded = st.uploaded(); pending = st.pending(); failed = st.failed();
		spaceLine = ClientBridge.core.spaceWarning();
		testLine = "";
		var core = ClientBridge.core;
		if (core.inWorld() && core.config.restoreTestDays > 0) {
			var t = core.store.forWorld(core.currentWorldId()).stream().filter(m -> m.testedAt > 0).findFirst().orElse(null);
			if (t == null) { testLine = "Restore test: not run yet"; testColor = GREY; }
			else {
				long days = Math.max(0, (System.currentTimeMillis() - t.testedAt) / 86_400_000L);
				String when = days == 0 ? "today" : days + " day(s) ago";
				boolean ok = "OK".equals(t.testResult);
				testLine = ok ? "Restore test: OK " + when : t.testResult.startsWith("FAILED") ? "Restore test FAILED " + when + " - make a new backup" : "Restore test " + when + ": " + t.testResult;
				testColor = ok ? GREEN : t.testResult.startsWith("FAILED") ? RED : GREY;
			}
		}
		lastBackup = st.newestTimestamp() == 0 ? "Never" : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(st.newestTimestamp()).atZone(ZoneId.systemDefault()));
	}

	@Override public void tick() { if (++ticks % 40 == 0) refresh(); }

	/** Draws a status line only if it still fits above the buttons; the most important lines come first. */
	private int line(GuiGraphicsExtractor g, String text, int x, int y, int color, int step) {
		if (y + 9 > this.height - 106) return y;
		left(g, text, x, y, color);
		return y + step;
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		CloudVaultCore core = ClientBridge.core;
		int x = this.width / 2 - 100, y = core.inWorld() ? 98 : 74;
		boolean off = core.inWorld() && core.isWorldDisabled(core.currentWorldId());
		y = line(g, core.inWorld() ? "World: " + core.currentWorldName() : "No world loaded (open one to save its state)", x, y, core.inWorld() ? WHITE : YELLOW, 12);
		if (off) y = line(g, "CloudVault is OFF for this world: no backups or uploads", x, y, RED, 12);
		boolean signed = ClientBridge.provider().isAuthenticated();
		y = line(g, "Cloud: " + ClientBridge.providerName() + (signed ? " - connected" : " - NOT connected"), x, y, signed ? GREEN : YELLOW, 12);
		if (core.isAutoPaused()) { long m = core.pausedMinutesLeft(); y = line(g, "Automatic backups PAUSED" + (m < 0 ? " (until resumed)" : " (" + m + " min left)"), x, y, YELLOW, 12); }
		var ph = core.activity.phase();
		String act = ph != com.cloudvault.backup.Activity.Phase.IDLE ? core.activity.describe() : pending > 0 ? "Waiting for upload (" + pending + ")" : "Idle";
		y = line(g, "Activity: " + act, x, y, ph != com.cloudvault.backup.Activity.Phase.IDLE ? YELLOW : GREY, 12);
		if (spaceLine != null) y = line(g, spaceLine, x, y, YELLOW, 12);
		y = line(g, "Last backup: " + lastBackup, x, y, GREY, 12);
		y = line(g, "Backups: " + uploaded + " in cloud (verified), " + pending + " pending, " + failed + " failed", x, y, failed > 0 ? RED : pending > 0 ? YELLOW : GREY, 12);
		if (!testLine.isEmpty()) y = line(g, testLine, x, y, testColor, 12);
		String prob = core.activity.lastProblem();
		if (!prob.isEmpty()) y = line(g, "Last problem: " + (prob.length() > 44 ? prob.substring(0, 43) + "..." : prob), x, y, RED, 12);
		if (core.inWorld() && !off) {
			var s = core.settings();
			if (s.gameplay.enabled) y = line(g, "Next gameplay backup in " + TimeUtil.formatDuration(core.gameplay.remaining(s.gameplay)), x, y, GREY, 11);
			if (s.periodic.enabled && core.periodic.nextDue() != null)
				y = line(g, "Next periodic backup: " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(core.periodic.nextDue()), x, y, GREY, 11);
		}
	}
}
