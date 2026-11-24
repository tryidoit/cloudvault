package com.cloudvault.client.gui;

import com.cloudvault.alerts.AlertManager;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.config.AlertSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/** Where alerts go: add a channel, edit its details, test it, switch it off, remove it. All addresses and tokens live in the secret store. */
public class AlertChannelsScreen extends BaseScreen {
	private int typeIdx = 0;
	private volatile String note = "";

	public AlertChannelsScreen(Screen parent) { super("Alert channels", parent); }
	@Override protected boolean wizardButton() { return true; }
	private void rebuild() { this.clearWidgets(); this.init(); }
	private AlertManager am() { return ClientBridge.core.alerts; }

	@Override protected void init() {
		header();
		int cx = this.width / 2, y = 44;
		List<AlertSettings.Channel> chs = List.copyOf(ClientBridge.core.config.alerts.channels);
		for (AlertSettings.Channel ch : chs) {
			if (y > this.height - 90) break;
			btn(Fmt.clip(ch.name.isBlank() ? AlertManager.typeLabel(ch.type) : ch.name, 22) + (am().complete(ch) ? "" : " (incomplete)"), cx - 150, y, 150, () -> this.minecraft.gui.setScreen(new ChannelEditScreen(this, ch)), "Edit the details of this channel.");
			btn(ch.enabled ? "On" : "Off", cx + 2, y, 30, () -> { ch.enabled = !ch.enabled; ClientBridge.settingsChanged(); rebuild(); });
			btn("Test", cx + 34, y, 40, () -> { note = "Sending..."; background("alert-test", () -> note = am().test(ch)); }, "Sends a test alert now.");
			btn("Remove", cx + 76, y, 54, () -> { am().removeChannel(ch); ClientBridge.settingsChanged(); rebuild(); });
			y += 24;
		}
		y = Math.max(y + 6, 44);
		String type = AlertManager.TYPES.get(typeIdx);
		btn("New channel type: " + AlertManager.typeLabel(type) + " (click)", cx - 150, this.height - 74, 300, () -> { typeIdx = (typeIdx + 1) % AlertManager.TYPES.size(); rebuild(); });
		btn("Add channel", cx - 150, this.height - 50, 300, () -> { var ch = am().addChannel(type); ClientBridge.settingsChanged(); this.minecraft.gui.setScreen(new ChannelEditScreen(this, ch)); });
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		if (ClientBridge.core.config.alerts.channels.isEmpty()) centered(g, "No channels yet. Add one below.", 50, GREY);
		if (!ClientBridge.core.config.alerts.enabled) centered(g, "Alerts are switched OFF (Alerts page).", 28, YELLOW);
		if (!note.isEmpty()) centered(g, Fmt.clip(note, 70), this.height - 88, YELLOW);
	}
}
