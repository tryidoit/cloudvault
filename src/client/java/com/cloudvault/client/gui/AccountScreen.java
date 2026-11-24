package com.cloudvault.client.gui;

import com.cloudvault.account.AccountManager;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * CloudVault account: this computer's settings live in a file in your cloud storage. Here: switch it on or off, set the password that
 * protects the keys inside the file, and load the settings another computer saved (a new computer does this once after connecting).
 */
public class AccountScreen extends BaseScreen {
	private static final int PAGE = 3;
	private volatile List<AccountManager.Profile> profiles = List.of();
	private volatile String note = "";
	private volatile boolean loading = false, rebuildNext = false;
	private String pw = "";
	private int pageIdx = 0;

	public AccountScreen(Screen parent) { super("Account & Devices", parent); refresh(); }
	@Override protected boolean wizardButton() { return true; }

	private AccountManager acc() { return ClientBridge.core.account; }
	private void rebuild() { this.clearWidgets(); this.init(); }
	@Override public void tick() { if (rebuildNext) { rebuildNext = false; rebuild(); } }

	private void refresh() {
		loading = true;
		background("account-list", () -> {
			try { profiles = acc().listProfiles(); note = profiles.isEmpty() ? "No settings files in your cloud yet." : ""; }
			catch (Exception e) { note = Fmt.clip(e.getMessage() == null ? "Could not read the cloud" : e.getMessage(), 70); }
			finally { loading = false; rebuildNext = true; }
		});
	}

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 40;
		boolean on = acc().enabled();
		btn("CloudVault account: " + (on ? "ON" : "OFF"), cx - 150, y, 300, () -> {
			if (on) this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Turn the account off?", List.of("Settings stop syncing from this computer.", "Delete this computer's settings file from the cloud too?"), "Turn off and delete file", false, () -> { acc().disable(true); rebuildNext = true; refresh(); }));
			else { note = "Creating this computer's settings file..."; background("account-on", () -> { try { acc().enable(); note = "On"; } catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage(), 60); } refresh(); }); }
		}, "Keeps your settings in a file in your cloud so another computer can set itself up from it. Updated every time you change a setting.");
		y += 24;
		EditBox box = new EditBox(this.font, cx - 150, y + 12, 200, 20, Component.literal("Password"));
		box.setMaxLength(100); box.setResponder(v -> pw = v); this.addRenderableWidget(box);
		btn("Set password", cx + 54, y + 12, 96, () -> { acc().setPassword(pw); pw = ""; note = acc().password().isEmpty() ? "No password: keys are NOT stored in the cloud file." : "Password set. Keys are stored encrypted."; background("account-up", () -> { try { if (acc().enabled()) acc().uploadNow(); } catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage(), 60); } }); rebuild(); }, "Keys (alert webhooks, S3 secrets) are put in the cloud file only when a password protects them. Leave empty to keep them on this computer only.");
		y += 40;
		btn("Save my settings file now", cx - 150, y, 300, () -> background("account-up", () -> { try { acc().uploadNow(); note = "Saved"; refresh(); } catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage(), 60); } })).active = on;
		y += 26;
		var list = profiles;
		for (int i = pageIdx * PAGE; i < Math.min(list.size(), (pageIdx + 1) * PAGE); i++) {
			final var p = list.get(i);
			if (!p.mine()) btn("Use these", cx + 20, y, 70, () -> load(p), "Copies the settings of this computer onto this one. Your cloud login and the world list stay as they are.");
			btn("Delete", cx + 92, y, 58, () -> this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Delete settings file?", List.of(p.fileName()), "Delete", false, () -> background("account-del", () -> { try { acc().deleteProfile(p); } catch (Exception e) { note = "Failed: " + Fmt.clip(e.getMessage(), 60); } refresh(); }))));
			y += 30;
		}
		btn("< Prev", cx - 150, this.height - 26, 60, () -> { pageIdx = Math.max(0, pageIdx - 1); rebuild(); });
		btn("Next >", cx - 86, this.height - 26, 60, () -> { pageIdx = Math.min(pageIdx + 1, Math.max(0, (profiles.size() - 1) / PAGE)); rebuild(); });
		btn("Refresh", cx - 20, this.height - 26, 60, this::refresh).active = !loading;
		btn("Done", cx + 50, this.height - 26, 100, this::onClose);
	}

	private void load(AccountManager.Profile p) {
		final String password = pw;
		note = "Loading...";
		background("account-load", () -> {
			var r = acc().applyProfile(p, password);
			note = r.ok() ? r.message() : r.needsPassword() ? "Type the account password above, then press Use these again. (" + r.message() + ")" : "Failed: " + r.message();
			ClientBridge.applyMirror();
			rebuildNext = true;
		});
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, y = 66;
		left(g, acc().password().isEmpty() ? "Account password (empty = keys stay on this computer)" : "Account password (set - type a new one to change)", cx - 150, 64, GREY);
		y = 130;
		var list = profiles;
		for (int i = pageIdx * PAGE; i < Math.min(list.size(), (pageIdx + 1) * PAGE); i++) {
			var p = list.get(i);
			left(g, Fmt.clip(p.deviceName() + " (" + p.os() + ")" + (p.mine() ? " - this PC" : ""), 26), cx - 150, y + 1, p.mine() ? GREEN : WHITE);
			left(g, Fmt.clip("MC " + p.minecraft() + "  " + Fmt.date(p.savedAt()) + (p.hasSecrets() ? "  keys" : ""), 28), cx - 150, y + 13, GREY);
			y += 30;
		}
		if (!note.isEmpty()) centered(g, Fmt.clip(note, 72), this.height - 42, note.startsWith("Failed") ? RED : YELLOW);
		if (!acc().enabled() && !ClientBridge.core.config.account.importedFrom.isEmpty()) centered(g, "Loaded from " + ClientBridge.core.config.account.importedFrom, this.height - 54, GREY);
	}
}
