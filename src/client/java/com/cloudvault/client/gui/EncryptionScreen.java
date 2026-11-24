package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** EXPERIMENTAL client-side encryption: set up a key, see the recovery key once, turn it on or off, or unlock an existing key. */
public class EncryptionScreen extends BaseScreen {
	@Override protected boolean wizardButton() { return true; }
	private String password = "", confirm = "", unlockText = "";
	private String note = "";
	private String recoveryKey = null; // shown once, right after the key is created
	private boolean unlockMode = false;
	private volatile boolean rebuildNext = false;

	@Override public void tick() { if (rebuildNext) { rebuildNext = false; rebuild(); } }

	public EncryptionScreen(Screen parent) { super("Encryption (experimental)", parent); }

	private CloudVaultCore core() { return ClientBridge.core; }

	private EditBox box(int x, int y, int w, String name, java.util.function.Consumer<String> set) {
		EditBox b = new EditBox(this.font, x, y, w, 20, Component.literal(name));
		b.setMaxLength(120); b.setResponder(s -> set.accept(s));
		this.addRenderableWidget(b);
		return b;
	}

	private void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected void init() {
		header();
		int cx = this.width / 2, w = 240;
		if (recoveryKey != null) {
			btn("I saved my recovery key - continue", cx - w / 2, this.height - 52, w, () -> { recoveryKey = null; rebuild(); });
		} else if (core().encryption.isSetUp()) {
			btn("Encrypt new backups: " + (core().config.encryptionEnabled ? "ON" : "OFF") + " (EXPERIMENTAL)", cx - w / 2, 64, w, () -> {
				core().config.encryptionEnabled = !core().config.encryptionEnabled; core().saveConfig(); rebuild();
			});
			btn("Remove key from this computer", cx - w / 2, 92, w, () -> this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Remove the encryption key?",
				List.of("Encrypted backups can then ONLY be opened with your password", "or recovery key. Without them they are lost forever."), "Remove key", true,
				() -> { try { core().encryption.forget(); core().config.encryptionEnabled = false; core().saveConfig(); } catch (Exception e) { ClientBridge.toast("CloudVault", e.getMessage()); } })));
		} else if (unlockMode) {
			box(cx - w / 2, 70, w, "Password or recovery key", s -> unlockText = s);
			btn("Unlock using a backup in the cloud", cx - w / 2, 98, w, () -> { note = "Unlocking..."; background("unlock", () -> {
				try { core().cloud.unlockEncryption(unlockText.trim()); note = "Unlocked"; ClientBridge.toast("CloudVault", "Encryption unlocked"); }
				catch (Exception e) { note = e.getMessage(); }
			}); });
			btn("Back", cx - w / 2, 122, w, () -> { unlockMode = false; rebuild(); });
		} else {
			box(cx - w / 2, 72, w, "Password", s -> password = s);
			box(cx - w / 2, 108, w, "Confirm password", s -> confirm = s);
			btn("Create encryption key", cx - w / 2, 134, w, this::create);
			if (core().store.all().stream().anyMatch(m -> m.encrypted)) btn("I already have a key: unlock it", cx - w / 2, 158, w, () -> { unlockMode = true; rebuild(); });
		}
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	private void create() {
		if (!password.equals(confirm)) { note = "The two passwords do not match"; return; }
		if (password.length() < 8) { note = "Use at least 8 characters"; return; }
		note = "Creating key (takes a moment)...";
		char[] pw = password.toCharArray();
		background("encryption-setup", () -> {
			String rec = core().encryption.setUp(pw);
			recoveryKey = rec; // show it first: it must never be lost because a file write failed
			core().config.encryptionEnabled = true; core().saveConfig();
			password = ""; confirm = ""; note = "";
			rebuildNext = true;
			try { Files.writeString(ClientBridge.rootDir().resolve("RECOVERY-KEY.txt"), "CloudVault recovery key (keep this somewhere safe, then delete this file):\n" + rec + "\n"); }
			catch (Exception e) { note = "Could not write RECOVERY-KEY.txt - copy the key from the screen"; }
		});
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, l = cx - 120, y = 28;
		if (recoveryKey != null) {
			centered(g, "SAVE THIS RECOVERY KEY NOW", y, RED); y += 16;
			centered(g, recoveryKey.substring(0, 29), y, WHITE); y += 12;
			centered(g, recoveryKey.substring(30), y, WHITE); y += 18;
			left(g, "It also opens your backups if you forget the password.", l, y, GREY); y += 11;
			left(g, "It is shown only once. A copy was written to:", l, y, GREY); y += 11;
			left(g, "  cloudvault/RECOVERY-KEY.txt  (move it somewhere safe, then delete it)", l, y, YELLOW); y += 16;
			left(g, "If you lose BOTH the password and this key, encrypted backups", l, y, RED); y += 11;
			left(g, "can never be recovered - not even by us or your cloud provider.", l, y, RED);
			if (!note.isEmpty()) centered(g, note, this.height - 66, YELLOW);
			return;
		}
		left(g, "EXPERIMENTAL - may change; keep a normal backup too.", l, y, YELLOW); y += 11;
		left(g, "Backups are encrypted on this computer (AES-256-GCM) before upload.", l, y, GREY); y += 11;
		left(g, "The cloud never receives your password or key.", l, y, GREY);
		if (core().encryption.isSetUp()) {
			left(g, "Key is set up on this computer.", l, 50, GREEN);
			left(g, "Only NEW uploads are encrypted; older cloud copies stay as they are.", l, 122, GREY);
			left(g, "The copy on this computer stays unencrypted (it is your own disk).", l, 133, GREY);
			left(g, "Lost password AND recovery key = backups cannot be recovered.", l, 144, RED);
		} else if (unlockMode) {
			left(g, "Enter your password or recovery key (visible while typing):", l, 58, GREY);
		} else {
			left(g, "Choose a password (visible while typing, so check nobody is watching):", l, 60, GREY);
			left(g, "Confirm password:", l, 96, GREY);
		}
		if (!note.isEmpty()) centered(g, note, this.height - 42, "Unlocked".equals(note) ? GREEN : YELLOW);
	}
}
