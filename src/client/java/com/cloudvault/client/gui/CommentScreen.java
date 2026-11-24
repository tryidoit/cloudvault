package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * "Add a comment" window shown before a backup. In singleplayer a screen like this pauses the game while it is open.
 * The callback is ALWAYS called exactly once: text (may be empty = no comment), or null = skip this backup. Esc = back up without a comment.
 */
public class CommentScreen extends BaseScreen {
	@Override protected boolean showCrumbs() { return false; }
	private final BackupType type;
	private final String world;
	private final Consumer<String> done;
	private String text = "";
	private boolean finished = false;

	public CommentScreen(Screen parent, BackupType type, String world, Consumer<String> done) {
		super("Backup comment", parent);
		this.type = type; this.world = world; this.done = done;
	}

	@Override protected void init() {
		int cx = this.width / 2, y = this.height / 2 - 14;
		EditBox box = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("Comment"));
		box.setMaxLength(200); box.setValue(text); box.setResponder(t -> text = t);
		this.addRenderableWidget(box);
		this.setFocused(box); box.setFocused(true);
		btn("Save with comment", cx - 150, y + 28, 146, () -> finish(text.isBlank() ? "" : text.trim()));
		btn("Back up without comment", cx + 4, y + 28, 146, () -> finish(""));
		btn("Skip this backup", cx - 75, y + 52, 150, () -> finish(null), "No backup is made this time.");
	}

	private void finish(String result) {
		if (finished) return;
		finished = true;
		this.minecraft.gui.setScreen(parent);
		done.accept(result);
	}

	/** Esc: the backup still happens, just without a comment (a backup is never lost silently). */
	@Override public void onClose() { finish(""); }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int cx = this.width / 2, y = this.height / 2 - 52;
		centered(g, "Comment for the " + type.label() + " backup", y, WHITE);
		String w = world == null ? "" : world.length() > 40 ? world.substring(0, 39) + "..." : world;
		centered(g, "of \"" + w + "\"", y + 12, GREY);
		centered(g, "It is saved with the backup in the cloud (inside the backup file).", y + 24, GREY);
	}
}
