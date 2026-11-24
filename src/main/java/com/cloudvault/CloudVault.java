package com.cloudvault;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint. CloudVault is client-side; real startup happens in CloudVaultClient. */
public class CloudVault implements ModInitializer {
	public static final String MOD_ID = "cloudvault";
	public static final String VERSION = "0.1.0";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("CloudVault common init");
	}
}
