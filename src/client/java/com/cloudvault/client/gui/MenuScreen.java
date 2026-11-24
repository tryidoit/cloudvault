package com.cloudvault.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** A page that is only a list of buttons leading to other pages (Backups, Cloud, Separate Pages). */
public class MenuScreen extends BaseScreen {
	private final String blurb;
	private final List<PageTree.Entry> entries;
	private final boolean settingsPage;

	public MenuScreen(Screen parent, String title, String blurb, List<PageTree.Entry> entries) {
		super(title, parent);
		this.blurb = blurb; this.entries = entries; this.settingsPage = !title.equals("Separate Pages");
	}

	@Override protected boolean wizardButton() { return settingsPage; }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		for (PageTree.Entry e : entries) {
			btn(e.label(), cx - 110, y, 220, () -> this.minecraft.gui.setScreen(e.open().apply(this)), e.tip().isEmpty() ? null : e.tip());
			y += 24;
		}
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) { if (!blurb.isEmpty()) centered(g, blurb.length() > 70 ? blurb.substring(0, 69) + "..." : blurb, 26, GREY); }
}
