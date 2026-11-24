package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared helpers so every CloudVault screen stays small. */
public abstract class BaseScreen extends Screen {
	protected static final int WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA, GREEN = 0xFF55FF55, RED = 0xFFFF5555, YELLOW = 0xFFFFFF55;
	protected final Screen parent;
	private final String heading;

	protected BaseScreen(String heading, Screen parent) {
		super(Component.literal(heading));
		this.heading = heading;
		this.parent = parent;
	}

	@Override public void onClose() { ClientBridge.settingsChanged(); this.minecraft.gui.setScreen(parent); }

	/** The path shown top-left on every CloudVault screen, e.g. CloudVault > Main Settings > Cloud > Cloud Management. Built from the chain of parent screens. */
	protected String[] crumbs() {
		List<String> l = new ArrayList<>();
		if (parent instanceof BaseScreen b && b.showCrumbs()) l.addAll(Arrays.asList(b.crumbs())); else l.add("CloudVault");
		l.add(heading);
		return l.toArray(new String[0]);
	}

	/** Dialogs (confirmations, comment box, wizards) show their title in the middle instead of a path. */
	protected boolean showCrumbs() { return true; }

	/** Settings pages get the setup-wizard button in the top-right corner (Quick setup / Advanced setup). */
	protected boolean wizardButton() { return false; }

	/** Call first in init(): adds what every page has in common (the wizard button). */
	protected void header() {
		if (wizardButton()) btn("Setup wizard", this.width - 92, 4, 88, () -> this.minecraft.gui.setScreen(new WizardChooserScreen(this)), "Walks you through the settings step by step: Quick setup or Advanced setup (asks for every setting).");
	}

	protected Button btn(String label, int x, int y, int w, Runnable action) { return btn(label, x, y, w, action, null); }

	/** @param tip hover explanation (null = none). Only add one where a normal player could be unsure what the control does. */
	protected Button btn(String label, int x, int y, int w, Runnable action, String tip) {
		Button b = makeBtn(label, x, y, w, action, tip);
		this.addRenderableWidget(b);
		return b;
	}

	/** Builds a button without adding it to the screen (used for rows that are scrolled out of view). */
	protected Button makeBtn(String label, int x, int y, int w, Runnable action, String tip) {
		var builder = Button.builder(Component.literal(label), bb -> action.run()).bounds(x, y, w, 20);
		if (tip != null) builder.tooltip(Tooltip.create(Component.literal(tip)));
		return builder.build();
	}

	protected void centered(GuiGraphicsExtractor g, String s, int y, int color) {
		g.text(this.font, s, (this.width - this.font.width(s)) / 2, y, color, true);
	}

	protected void left(GuiGraphicsExtractor g, String s, int x, int y, int color) { g.text(this.font, s, x, y, color, true); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		if (showCrumbs()) drawCrumbs(g); else centered(g, heading, 8, WHITE);
		draw(g, mouseX, mouseY);
	}

	private void drawCrumbs(GuiGraphicsExtractor g) {
		String[] c = crumbs();
		int room = this.width - 12 - (wizardButton() ? 92 : 0);
		int first = 0;
		while (first < c.length - 1 && this.font.width(String.join(" > ", Arrays.copyOfRange(c, first, c.length))) > room) first++;
		int x = 6;
		if (first > 0) { left(g, "... > ", x, 8, GREY); x += this.font.width("... > "); }
		for (int i = first; i < c.length; i++) {
			boolean last = i == c.length - 1;
			left(g, c[i], x, 8, last ? WHITE : GREY); x += this.font.width(c[i]);
			if (!last) { left(g, " > ", x, 8, GREY); x += this.font.width(" > "); }
		}
	}

	/** Subclasses draw their own text here (after the widgets). */
	protected void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {}

	/** Runs slow work off the game thread; any failure becomes a toast, never a crash. */
	protected interface Job { void run() throws Exception; }

	protected static void background(String name, Job r) {
		Thread t = new Thread(() -> {
			try { r.run(); } catch (Throwable e) {
				String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				ClientBridge.toast("CloudVault", m.length() > 100 ? m.substring(0, 100) : m);
			}
		}, "CloudVault-" + name);
		t.setDaemon(true);
		t.start();
	}
}
