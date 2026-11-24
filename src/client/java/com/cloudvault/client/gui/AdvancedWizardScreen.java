package com.cloudvault.client.gui;

import com.cloudvault.client.ClientBridge;
import com.cloudvault.settings.SettingsRegistry;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Advanced setup: the generic page renderer, one registry page after another, with Back / Next. Because it walks the registry, it asks
 * for every setting there is (a test makes sure the registry covers the whole config). The pages' own buttons (logins, alert channels,
 * keys) are available on the page they belong to.
 */
public class AdvancedWizardScreen extends PageScreen {
	private final int index; private final List<String> keys; private final Screen exit;

	public static AdvancedWizardScreen start(Screen exit) {
		var cfg = ClientBridge.core.config;
		if (!cfg.firstLaunchDone) { cfg.firstLaunchDone = true; ClientBridge.settingsChanged(); }
		return new AdvancedWizardScreen(exit, PageTree.allPageKeys(), 0);
	}

	private AdvancedWizardScreen(Screen exit, List<String> keys, int index) {
		super(exit, PageTree.REG.page(keys.get(index)));
		this.exit = exit; this.keys = keys; this.index = index;
	}

	@Override protected boolean wizardButton() { return false; }
	@Override protected String[] crumbs() { return new String[]{"CloudVault", "Advanced setup", (index + 1) + "/" + keys.size() + ": " + page.key().replace("/", " > ")}; }

	@Override protected void footer(int cx) {
		btn("< Back", cx - 150, this.height - 26, 70, () -> go(index - 1)).active = index > 0;
		boolean last = index == keys.size() - 1;
		btn(last ? "Finish" : "Next >", cx - 74, this.height - 26, 100, () -> { if (last) onClose(); else go(index + 1); });
		btn("Exit setup", cx + 30, this.height - 26, 80, this::onClose);
	}

	private void go(int i) { ClientBridge.settingsChanged(); this.minecraft.gui.setScreen(new AdvancedWizardScreen(exit, keys, i)); }
}
