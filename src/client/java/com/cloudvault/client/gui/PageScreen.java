package com.cloudvault.client.gui;

import com.cloudvault.CloudVaultCore;
import com.cloudvault.client.ClientBridge;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.WorldSettings;
import com.cloudvault.settings.SettingsRegistry;
import com.cloudvault.settings.SettingsRegistry.Kind;
import com.cloudvault.settings.SettingsRegistry.Root;
import com.cloudvault.settings.SettingsRegistry.Setting;
import com.google.gson.Gson;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Renders ONE page of the settings registry: a toggle or choice is a button, a number is -10 -1 [box] +1 +10, text is a box.
 * Backup-type settings edit this world's own settings when it has them, otherwise the global defaults. Scrolls with the wheel
 * (see CloudVaultClient) or the Up/Down buttons. The Advanced wizard uses the same renderer, one page after another.
 */
public class PageScreen extends BaseScreen {
	private static final int TOP = 40, TOGGLE = 24, ROW = 38;

	protected final SettingsRegistry.Page page;
	private int scroll = 0, contentBottom = 0, maxScroll = 0;
	private boolean inContent = false;
	private final Map<Integer, Supplier<String>> labels = new HashMap<>();
	private String note = "";

	private final String scopeId, scopeName; // a specific world (from the world list), or null = the open world / the defaults

	public PageScreen(Screen parent, SettingsRegistry.Page page) { this(parent, page, null, null); }

	public PageScreen(Screen parent, SettingsRegistry.Page page, String worldId, String worldName) { super(page.title(), parent); this.page = page; this.scopeId = worldId; this.scopeName = worldName; }

	/** The world whose own settings this page edits: the chosen one, else the open one, else none (global defaults). */
	private String worldId() { return scopeId != null ? scopeId : core().inWorld() ? core().currentWorldId() : null; }
	private String worldName() { return scopeName != null ? scopeName : core().currentWorldName(); }

	@Override protected boolean wizardButton() { return true; }

	protected CloudVaultCore core() { return ClientBridge.core; }

	private Object rootFor(Setting s) { return s.root == Root.CONFIG ? core().config : target(); }

	private BackupSettings target() {
		String id = worldId();
		if (id != null) { WorldSettings w = core().config.worlds.get(id); if (w != null && w.useOverride) return w.settings; }
		return core().config.global;
	}

	private boolean hasBackupSettings() { for (Setting s : page.settings) if (s.root == Root.BACKUP) return true; return false; }

	/** Saved and applied; the timer is re-planned because a schedule may have changed. */
	private void edited() {
		ClientBridge.settingsChanged();
		if (core().inWorld()) core().periodic.reschedule(core().settings().periodic, java.time.ZonedDateTime.now());
	}

	private void changed() { edited(); rebuild(); }
	protected void rebuild() { this.clearWidgets(); this.init(); }

	@Override protected net.minecraft.client.gui.components.Button btn(String label, int x, int y, int w, Runnable action, String tip) {
		if (inContent) {
			contentBottom = Math.max(contentBottom, y + 20);
			if (y < TOP || y + 20 > this.height - 34) return makeBtn(label, x, y, w, action, tip); // scrolled out of view
		}
		return super.btn(label, x, y, w, action, tip);
	}

	@Override protected void init() {
		header();
		labels.clear(); note = "";
		int cx = this.width / 2, y = TOP - scroll;
		inContent = true; contentBottom = 0;
		if (worldId() != null && hasBackupSettings()) {
			String id = worldId(); WorldSettings w = core().config.worldSettings(id);
			btn("This world has its own settings: " + (w.useOverride ? "ON" : "OFF"), cx - 130, y, 260, () -> {
				if (!w.useOverride) { Gson g = new Gson(); w.settings = g.fromJson(g.toJson(core().config.global), BackupSettings.class); }
				w.useOverride = !w.useOverride; w.lastKnownName = worldName(); changed();
			}, "ON: the backup settings on this page change only this world. OFF: they are the defaults for all worlds.");
			y += TOGGLE;
		}
		for (Setting s : page.settings) y = row(s, y);
		for (PageTree.Entry e : PageTree.extras(page.key())) {
			btn(e.label(), cx - 130, y, 260, () -> this.minecraft.gui.setScreen(e.open().apply(this)), e.tip()); y += TOGGLE;
		}
		inContent = false;
		int viewH = this.height - 34 - TOP;
		maxScroll = Math.max(0, contentBottom + scroll - TOP - viewH + 4);
		if (maxScroll > 0) {
			btn("Up", this.width - 44, TOP, 36, () -> scrollBy(-1), "Scroll up (or use the mouse wheel)").active = scroll > 0;
			btn("Down", this.width - 44, this.height - 60, 36, () -> scrollBy(1), "Scroll down (or use the mouse wheel)").active = scroll < maxScroll;
		}
		footer(cx);
	}

	/** The bottom row: Done here; the Advanced wizard puts Back / Next there instead. */
	protected void footer(int cx) { btn("Done", cx - 50, this.height - 26, 100, this::onClose); }

	private int row(Setting s, int y) {
		int cx = this.width / 2, x0 = cx - 130;
		Object root = rootFor(s);
		switch (s.kind) {
			case TOGGLE -> {
				boolean v = Boolean.TRUE.equals(s.get(root));
				btn(s.label + ": " + (v ? "ON" : "OFF"), x0, y, 260, () -> { s.set(rootFor(s), !v); changed(); }, tip(s));
				return y + TOGGLE;
			}
			case CHOICE -> {
				btn(s.label + ": " + niceChoice(s.getText(root)) + " (click)", x0, y, 260, () -> {
					int i = Math.max(0, s.choices.indexOf(s.getText(rootFor(s))));
					s.set(rootFor(s), s.choices.get((i + 1) % s.choices.size()));
					if (s.path.equals("cloudProvider")) ClientBridge.selectProvider(s.getText(rootFor(s))); // re-points uploads
					changed();
				}, tip(s));
				return y + TOGGLE;
			}
			case INT -> {
				labels.put(y, () -> { int v = ((Number) s.get(rootFor(s))).intValue(); return s.label + ": " + (s.min == -1 && v == -1 ? "keep all" : v + (s.unit.isEmpty() ? "" : " " + s.unit)); });
				int by = y + 12, bx = x0;
				for (int step : new int[]{-10, -1}) { btn(step > 0 ? "+" + step : "" + step, bx, by, step == -10 ? 32 : 26, () -> stepBy(s, step)); bx += step == -10 ? 34 : 28; }
				if (inContent) contentBottom = Math.max(contentBottom, by + 20);
				if (!inContent || (by >= TOP && by + 20 <= this.height - 34)) {
					EditBox box = new EditBox(this.font, bx, by, 56, 20, Component.literal(s.label));
					box.setMaxLength(9); box.setValue(s.getText(root));
					box.setResponder(t -> { try { if (!t.isBlank() && !t.equals("-")) { s.set(rootFor(s), Integer.parseInt(t.trim())); edited(); } } catch (NumberFormatException ignored) { /* keep the last valid value */ } });
					this.addRenderableWidget(box);
				}
				bx += 58;
				for (int step : new int[]{1, 10}) { btn("+" + step, bx, by, step == 10 ? 32 : 26, () -> stepBy(s, step)); bx += step == 10 ? 34 : 28; }
				return y + ROW;
			}
			default -> { // TEXT
				labels.put(y, () -> s.label);
				int by = y + 12;
				if (inContent) contentBottom = Math.max(contentBottom, by + 20);
				if (!inContent || (by >= TOP && by + 20 <= this.height - 34)) {
					EditBox box = new EditBox(this.font, x0, by, 260, 20, Component.literal(s.label));
					box.setMaxLength(300); box.setValue(s.getText(root));
					box.setResponder(t -> { s.set(rootFor(s), t.trim()); edited(); });
					this.addRenderableWidget(box);
				}
				return y + ROW;
			}
		}
	}

	private static String niceChoice(String c) {
		return switch (c) {
			case "google_drive" -> "Google Drive"; case "s3" -> "S3-compatible"; case "folder" -> "Folder"; case "local" -> "Folder (NAS, USB, Dropbox...)";
			default -> c.isEmpty() ? c : c.charAt(0) + c.substring(1).toLowerCase();
		};
	}

	private static String tip(Setting s) { return s.tip.isEmpty() ? null : s.tip; }

	private void stepBy(Setting s, int step) {
		int cur = ((Number) s.get(rootFor(s))).intValue();
		int next = Math.max(s.min, Math.min(s.max, cur + step));
		if (next == cur) return;
		if (s.path.endsWith(".retention") && next == 0 && cur != 0) {
			this.minecraft.gui.setScreen(new CvConfirmScreen(this, "Warning", List.of("Keeping only the newest backup means there is no older one", "to fall back on if the newest turns out to be damaged."), "Use 0", false, () -> { s.set(rootFor(s), 0); changed(); }));
			return;
		}
		s.set(rootFor(s), next); changed();
	}

	private void scrollBy(int steps) { int n = Math.max(0, Math.min(maxScroll, scroll + steps * 26)); if (n != scroll) { scroll = n; rebuild(); } }

	/** Mouse-wheel events (hooked in CloudVaultClient). @return true when the wheel was used */
	public boolean onWheel(double vertical) { if (maxScroll <= 0 || vertical == 0) return false; scrollBy(vertical > 0 ? -1 : 1); return true; }

	@Override protected void draw(GuiGraphicsExtractor g, int mx, int my) {
		int x = this.width / 2 - 130;
		for (var e : labels.entrySet()) { int y = e.getKey(); if (y >= TOP - 4 && y + 10 <= this.height - 34) left(g, e.getValue().get(), x, y, WHITE); }
		boolean world = worldId() != null && hasBackupSettings();
		if (world) { WorldSettings w = core().config.worlds.get(worldId()); String n = worldName(); centered(g, w != null && w.useOverride ? "Editing \"" + n + "\" only" : "Editing the defaults for all worlds", 26, GREY); }
		else if (hasBackupSettings()) centered(g, "Editing the defaults for all worlds (open a world to give it its own)", 26, GREY);
		if (!note.isEmpty()) centered(g, note, this.height - 40, YELLOW);
	}
}
