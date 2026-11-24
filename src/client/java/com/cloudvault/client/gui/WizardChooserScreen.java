package com.cloudvault.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** The small chooser behind the "Setup wizard" button: Quick setup (the essentials) or Advanced setup (asks for every setting). */
public class WizardChooserScreen extends BaseScreen {
	public WizardChooserScreen(Screen parent) { super("Setup wizard", parent); }

	@Override protected void init() {
		int cx = this.width / 2, y = this.height / 2 - 20;
		btn("Quick setup", cx - 110, y, 220, () -> this.minecraft.gui.setScreen(new QuickWizardScreen(parent)), "Cloud, when to back up, incremental backups, sync, alerts: about a minute.");
		btn("Advanced setup", cx - 110, y + 26, 220, () -> this.minecraft.gui.setScreen(AdvancedWizardScreen.start(parent)), "Walks through every page of the settings, so every single setting is asked once.");
		btn("Cancel", cx - 50, y + 62, 100, () -> this.minecraft.gui.setScreen(parent));
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		centered(g, "Quick setup covers the essentials.", this.height / 2 - 50, GREY);
		centered(g, "Advanced setup asks for every single setting.", this.height / 2 - 38, GREY);
	}
}
