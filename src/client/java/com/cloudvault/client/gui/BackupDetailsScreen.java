package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.restore.RestoreTester;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** One backup: facts, plus pin, note, export, open folder and "test restore". */
public class BackupDetailsScreen extends BaseScreen {
	private BackupMetadata b;
	private String noteText;
	private final String originalNote;
	private volatile boolean rebuildNext = false;

	public BackupDetailsScreen(Screen parent, BackupMetadata b) {
		super("Backup Details", parent);
		this.b = b;
		this.originalNote = b.note == null ? "" : b.note;
		this.noteText = originalNote;
	}

	private CloudVaultCore core() { return ClientBridge.core; }
	private void reload() { core().store.find(b.backupId).ifPresent(m -> b = m); }
	private void rebuild() { this.clearWidgets(); this.init(); }
	@Override public void tick() { if (rebuildNext) { rebuildNext = false; reload(); rebuild(); } }

	@Override protected void init() {
		header();
		int cx = this.width / 2;
		EditBox note = new EditBox(this.font, cx - 200, this.height - 86, 400, 20, Component.literal("Note"));
		note.setMaxLength(80); note.setValue(noteText); note.setResponder(t -> noteText = t);
		this.addRenderableWidget(note);
		int y = this.height - 54;
		btn(b.pinned ? "Unpin" : "Pin", cx - 200, y, 70, () -> { commitNote(); try { core().setPinned(b.backupId, !b.pinned); } catch (Exception e) { ClientBridge.toast("CloudVault", "Could not save"); } reload(); rebuild(); },
			"A pinned backup is never deleted by retention and does not count against the limits. You can still delete it yourself.");
		btn("Test restore", cx - 126, y, 90, () -> background("test-restore", () -> {
			var r = core().runTest(core().store.find(b.backupId).orElse(b), true);
			ClientBridge.toast("CloudVault", r.outcome() == RestoreTester.Outcome.OK ? "Restore test passed" : r.message());
			rebuildNext = true;
		}), "Unpacks this backup into a temporary folder and checks it looks like a world. Downloads it first if it is only in the cloud. Never touches your worlds.");
		btn("Export ZIP", cx - 32, y, 80, () -> background("export", () -> {
			Path out = ClientBridge.rootDir().resolve("exports").resolve(b.fileName());
			core().cloud.exportZip(b, out);
			ClientBridge.toast("CloudVault", "Saved to cloudvault/exports");
			ClientBridge.openFolder(out.getParent());
		}), "Saves a normal ZIP of this backup into .minecraft/cloudvault/exports.");
		btn("Open folder", cx + 52, y, 90, () -> ClientBridge.openFolder(core().store.zipPath(b).getParent()), "Opens the folder with this world's local backup files.");
		btn("Back", cx + 150, y, 50, this::onClose);
	}

	private void commitNote() {
		if (noteText.equals(originalNote)) return;
		try { core().setNote(b.backupId, noteText); } catch (Exception e) { ClientBridge.toast("CloudVault", "Could not save the note"); }
	}

	@Override public void onClose() { commitNote(); super.onClose(); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int x = this.width / 2 - 200, y = 26;
		String test = b.testedAt == 0 ? "never tested" : b.testResult + "  (" + BackupBrowserScreen.date(b.testedAt) + ")";
		int testColor = b.testedAt == 0 ? GREY : "OK".equals(b.testResult) ? GREEN : b.testResult.startsWith("FAILED") ? RED : GREY;
		String cloud = BackupBrowserScreen.cloud(b) + (b.remoteId == null ? "" : " - " + (b.provider().equals("s3") ? "S3 storage" : "Google Drive")) + (b.encrypted ? " - encrypted (experimental)" : "");
		String[][] rows = {
			{"World", b.worldName + (b.pinned ? "   [PINNED]" : "")}, {"Date", BackupBrowserScreen.date(b)}, {"Type", b.backupType.label()},
			{"Minecraft", String.valueOf(b.minecraftVersion)}, {"Cloud", cloud}, {"Integrity", BackupBrowserScreen.integrity(b)},
			{"Size", BackupBrowserScreen.size(b.fileSize)}, {"Restore test", test}, {"Backup ID", b.backupId},
			{"SHA-256", b.sha256 == null ? "checked on first restore" : b.sha256.substring(0, 32)}, {"", b.sha256 == null ? "" : b.sha256.substring(32)},
			{"Last error", b.lastError == null ? "-" : b.lastError}, {"Comment", b.comment == null || b.comment.isBlank() ? "-" : (b.comment.replace('\n', ' ').length() > 52 ? b.comment.replace('\n', ' ').substring(0, 51) + "..." : b.comment.replace('\n', ' '))}
		};
		for (String[] r : rows) {
			left(g, r[0] + (r[0].isEmpty() ? "" : ":"), x, y, GREY);
			left(g, r[1], x + 80, y, r[0].equals("Restore test") ? testColor : WHITE);
			y += 11;
		}
		left(g, "Note (saved when you leave this screen):", x, this.height - 98, GREY);
	}
}
