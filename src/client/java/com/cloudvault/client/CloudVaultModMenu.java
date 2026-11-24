package com.cloudvault.client;

import com.cloudvault.client.gui.CloudVaultScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu integration: the "Configure" button in the mod list opens the CloudVault screen. Only loaded when Mod Menu is installed. */
public class CloudVaultModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CloudVaultScreen::new;
	}
}
