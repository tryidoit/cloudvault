package com.cloudvault.client;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.BackupType;
import com.cloudvault.client.gui.CloudVaultScreen;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLScancode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class CloudVaultClient implements ClientModInitializer {
	private int tickCounter = 0;
	private boolean syncStarted = false;
	private static volatile boolean openScreenNextTick = false;

	private static int pauseCmd(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src, int minutes) {
		ClientBridge.core.pauseAuto(minutes);
		src.sendFeedback(Component.literal("CloudVault: automatic backups paused " + (minutes <= 0 ? "until you run /cloudvault resume." : "for " + minutes + " minute(s).") + " Manual backups still work."));
		return Command.SINGLE_SUCCESS;
	}

	/** Once a second while playing: tells the smart triggers where the player is and how healthy, and backs up when one fires. */
	private static void smartTick(net.minecraft.client.Minecraft mc) {
		var core = ClientBridge.core;
		var p = mc.player;
		if (p == null || mc.level == null || !core.inWorld()) return;
		try {
			String dim = mc.level.dimension().identifier().toString();
			float hearts = (p.getHealth() + p.getAbsorptionAmount()) / 2f;
			double dx = p.getX() - lastX, dy = p.getY() - lastY, dz = p.getZ() - lastZ;
			boolean moved = dx * dx + dy * dy + dz * dz > 0.0004 || p.getYRot() != lastYaw;
			lastX = p.getX(); lastY = p.getY(); lastZ = p.getZ(); lastYaw = p.getYRot();
			core.smartCheck(new com.cloudvault.smart.SmartTriggers.Observation(dim, hearts, moved)).ifPresent(reason -> {
				if (CommentPrompts.busy()) return;
				ClientBridge.saveAndBackup(BackupType.EVENT);
				ClientBridge.toast("CloudVault", "Smart backup: " + reason);
			});
		} catch (Throwable t) { CloudVault.LOGGER.warn("Smart trigger check failed: {}", t.toString()); }
	}
	private static double lastX, lastY, lastZ; private static float lastYaw;

	@Override
	public void onInitializeClient() {
		try {
			ClientBridge.init();
		} catch (Throwable t) { // never crash Minecraft because of us
			CloudVault.LOGGER.error("CloudVault failed to start and is disabled: {}", t.toString());
			return;
		}

		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CloudVault.MOD_ID, "main"));
		KeyMapping openKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.cloudvault.open", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_B, cat));

		// Singleplayer world lifecycle. SERVER_STARTED = world fully loaded; SERVER_STOPPED = saved and closed.
		ServerLifecycleEvents.SERVER_STARTING.register(srv -> { if (srv.isSingleplayer()) ClientBridge.worldStarting(srv); }); // before the world loads
		ServerLifecycleEvents.SERVER_STARTED.register(srv -> { if (srv.isSingleplayer()) ClientBridge.worldStarted(srv); });
		ServerLifecycleEvents.SERVER_STOPPED.register(srv -> { if (srv.isSingleplayer()) ClientBridge.worldStopped(); });

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			CommentPrompts.tick(mc); // comment window, when one is waiting
			if (openScreenNextTick) { openScreenNextTick = false; mc.gui.setScreen(new CloudVaultScreen(null)); }
			while (openKey.consumeClick()) {
				if (mc.gui.screen() == null || mc.gui.screen() instanceof PauseScreen) mc.gui.setScreen(new CloudVaultScreen(mc.gui.screen()));
			}
			if (++tickCounter % 20 != 0) return; // once per second is plenty
			if (!ClientBridge.core.config.firstLaunchDone && mc.gui.screen() instanceof TitleScreen) mc.gui.setScreen(new com.cloudvault.client.gui.QuickWizardScreen(mc.gui.screen()));
			if (!syncStarted) { syncStarted = true; if (ClientBridge.core.config.sync.autoDownload) ClientBridge.syncNow(false); } // cloud sync check once per start
			if (CommentPrompts.busy()) return; // a comment window is open: no new automatic backups meanwhile
			BackupType due = ClientBridge.core.pollScheduled();
			if (due != null) ClientBridge.saveAndBackup(due);
			ClientBridge.core.maybeRestoreTest(); // cheap: returns at once unless a test is due
			smartTick(mc);
			ClientBridge.core.watchdog(); // cheap: acts at most hourly
		});

		// Small ON/OFF note while playing (can be switched off in Settings > Worlds). Cheap: a map lookup and one text draw per frame, only in a world.
		final String[] hudText = {"CloudVault: ON", "CloudVault: OFF", "CloudVault: ON (paused)"};
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cloudvault", "world_indicator"), (graphics, deltaTracker) -> {
			var core = ClientBridge.core;
			if (core == null || !core.config.showWorldIndicator || !core.inWorld()) return;
			boolean off = core.isWorldDisabled(core.currentWorldId());
			graphics.text(net.minecraft.client.Minecraft.getInstance().font, hudText[off ? 1 : core.isAutoPaused() ? 2 : 0], 4, 4, off ? 0xFFFF5555 : 0xFF55FF55);
		});

		// Chat commands (work even if the key or pause button misbehave): /cloudvault, /cloudvault backup
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("cloudvault")
				.executes(ctx -> { openScreenNextTick = true; return Command.SINGLE_SUCCESS; })
				.then(ClientCommands.literal("pause").executes(ctx -> pauseCmd(ctx.getSource(), 0))
					.then(ClientCommands.argument("minutes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10080))
						.executes(ctx -> pauseCmd(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "minutes")))))
				.then(ClientCommands.literal("resume").executes(ctx -> {
					ClientBridge.core.resumeAuto();
					ctx.getSource().sendFeedback(Component.literal("CloudVault: automatic backups resumed."));
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommands.literal("backup").executes(ctx -> {
					if (!ClientBridge.core.inWorld()) { ctx.getSource().sendFeedback(Component.literal("CloudVault: open a singleplayer world first.")); return 0; }
					if (!ClientBridge.saveAndBackup(BackupType.MANUAL)) { ctx.getSource().sendFeedback(Component.literal("CloudVault is turned off for this world.")); return 0; }
					ctx.getSource().sendFeedback(Component.literal("CloudVault: backup started..."));
					return Command.SINGLE_SUCCESS;
				}))));

		// "CloudVault" button on the pause menu (top-left corner so it never collides with vanilla layout).
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof com.cloudvault.client.gui.PageScreen pg)
				ScreenMouseEvents.afterMouseScroll(screen).register((scr, mouseX, mouseY, horizontal, vertical, consumed) -> pg.onWheel(vertical));
			// World selection screen: matched by class name so a package move in Minecraft cannot break compilation.
			String cn = screen.getClass().getName();
			if (cn.endsWith("SelectWorldScreen") || cn.contains("WorldSelection")) {
				if (ClientBridge.core.config.sync.autoDownload) ClientBridge.syncIfStale();
				Screens.getWidgets(screen).add(Button.builder(Component.literal("CloudVault Worlds"),
					b -> mc.gui.setScreen(new com.cloudvault.client.gui.WorldsScreen(screen))).bounds(6, 6, 104, 20).build());
				Screens.getWidgets(screen).add(Button.builder(Component.literal("Import from cloud"),
					b -> mc.gui.setScreen(new com.cloudvault.client.gui.ImportScreen(screen))).bounds(6, 28, 104, 20).build());
			}
			if (screen instanceof TitleScreen) // the same Main Settings page as the pause menu
				Screens.getWidgets(screen).add(Button.builder(Component.literal("CloudVault"), b -> mc.gui.setScreen(new CloudVaultScreen(screen))).bounds(6, 6, 80, 20).build());
			if (screen instanceof PauseScreen) {
				var cfg = ClientBridge.core.config;
				String label = cfg.showWorldIndicator && ClientBridge.core.inWorld()
					? "CloudVault: " + (ClientBridge.core.isWorldDisabled(ClientBridge.core.currentWorldId()) ? "OFF" : "ON") : "CloudVault";
				Screens.getWidgets(screen).add(Button.builder(Component.literal(label),
					b -> mc.gui.setScreen(new CloudVaultScreen(screen))).bounds(6, 6, 110, 20).build());
			}
		});
	}
}
