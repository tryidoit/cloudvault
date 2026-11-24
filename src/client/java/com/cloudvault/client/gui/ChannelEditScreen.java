package com.cloudvault.client.gui;

import com.cloudvault.alerts.AlertManager;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.config.AlertSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/** The details of one alert channel. Secrets (addresses, tokens, passwords) are never shown again after saving: type to replace. */
public class ChannelEditScreen extends BaseScreen {
	private static final Map<String, String> LABELS = Map.ofEntries(
		Map.entry("url", "Webhook address (https://...)"), Map.entry("token", "Token"), Map.entry("chatId", "Chat ID"), Map.entry("server", "Server (empty = ntfy.sh)"),
		Map.entry("topic", "Topic"), Map.entry("user", "User key"), Map.entry("sid", "Account SID"), Map.entry("authToken", "Auth token"),
		Map.entry("from", "From"), Map.entry("to", "To"), Map.entry("host", "SMTP host"), Map.entry("port", "Port (587)"),
		Map.entry("security", "Security: starttls / ssl / none"), Map.entry("username", "Username"), Map.entry("password", "Password"));

	private final AlertSettings.Channel ch;
	private volatile String note = "";

	public ChannelEditScreen(Screen parent, AlertSettings.Channel ch) { super(AlertManager.typeLabel(ch.type), parent); this.ch = ch; }
	@Override protected boolean wizardButton() { return true; }
	private AlertManager am() { return ClientBridge.core.alerts; }

	@Override protected void init() {
		header();
		int cx = this.width / 2, l = cx - 155;
		EditBox name = new EditBox(this.font, l, 44, 310, 20, Component.literal("Name"));
		name.setMaxLength(40); name.setValue(ch.name); name.setResponder(v -> ch.name = v.trim()); this.addRenderableWidget(name);
		List<String> fields = AlertManager.FIELDS.getOrDefault(ch.type, List.of());
		for (int i = 0; i < fields.size(); i++) {
			String f = fields.get(i);
			int x = l + (i % 2) * 160, y = 84 + (i / 2) * 36;
			boolean wide = fields.size() == 1;
			EditBox b = new EditBox(this.font, x, y, wide ? 310 : 150, 20, Component.literal(f));
			b.setMaxLength(400);
			b.setValue(AlertManager.isSecretField(f) ? "" : am().field(ch, f));
			b.setResponder(v -> { if (!v.isBlank() || !AlertManager.isSecretField(f)) { am().setField(ch, f, v.trim()); ClientBridge.settingsChanged(); } });
			this.addRenderableWidget(b);
		}
		int by = 84 + ((fields.size() + 1) / 2) * 36;
		btn("Send a test alert", cx - 155, by, 310, () -> { note = "Sending..."; background("alert-test", () -> note = am().test(ch)); });
		btn("Done", cx - 50, this.height - 26, 100, this::onClose);
	}

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int l = this.width / 2 - 155;
		left(g, "Name (for your own reference)", l, 34, GREY);
		List<String> fields = AlertManager.FIELDS.getOrDefault(ch.type, List.of());
		for (int i = 0; i < fields.size(); i++) {
			String f = fields.get(i);
			String t = LABELS.getOrDefault(f, f) + (AlertManager.isSecretField(f) && am().field(ch, f).length() > 0 ? " (saved)" : "");
			left(g, Fmt.clip(t, 26), l + (i % 2) * 160, 74 + (i / 2) * 36, GREY);
		}
		int ny = 84 + ((fields.size() + 1) / 2) * 36 + 26;
		if (ch.type.equals("sms")) { left(g, "SMS needs a Twilio account: a phone number alone cannot send text messages.", l, ny, YELLOW); ny += 11; }
		if (ch.type.equals("email")) { left(g, "Use an app password for the mail account, not your normal password.", l, ny, GREY); ny += 11; }
		if (!note.isEmpty()) left(g, Fmt.clip(note, 70), l, Math.min(ny, this.height - 44), note.startsWith("Failed") ? RED : GREEN);
	}
}
