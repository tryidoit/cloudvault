package com.cloudvault.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** Our own confirm dialog. If requireAck is set, the confirm button stays disabled until the user ticks the box. */
public class CvConfirmScreen extends BaseScreen {
	@Override protected boolean showCrumbs() { return false; }
	private final List<String> lines;
	private final String confirmLabel;
	private final Runnable onConfirm;
	private final boolean requireAck;
	private boolean ack;
	private Button confirmBtn;

	public CvConfirmScreen(Screen parent, String title, List<String> lines, String confirmLabel, boolean requireAck, Runnable onConfirm) {
		super(title, parent);
		this.lines = lines; this.confirmLabel = confirmLabel; this.requireAck = requireAck; this.onConfirm = onConfirm;
	}

	@Override protected void init() {
		int cx = this.width / 2, y = 40 + lines.size() * 12 + 14;
		if (requireAck) {
			btn("[ ] I understand and want to continue", cx - 110, y, 220, () -> { ack = !ack; rebuild(); });
			y += 28;
		}
		btn("Cancel", cx - 105, y, 100, () -> this.minecraft.gui.setScreen(parent));
		confirmBtn = btn(confirmLabel, cx + 5, y, 100, () -> { this.minecraft.gui.setScreen(parent); onConfirm.run(); });
		confirmBtn.active = !requireAck || ack;
	}

	private void rebuild() {
		this.clearWidgets();
		this.init();
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int y = 40;
		for (String l : lines) { centered(g, l, y, WHITE); y += 12; }
		if (requireAck && ack) centered(g, "Confirmed - press the button to continue", 40 + lines.size() * 12 + 4, YELLOW);
	}
}
