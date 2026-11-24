package com.cloudvault.client;

import com.cloudvault.backup.BackupType;
import com.cloudvault.client.gui.CommentScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Waits for the right moment to show the comment window: not while the world is still loading, and for the LEAVE backup only once the
 * game is back at the title screen. Every request is answered exactly once, so a backup is never lost because a window did not appear.
 */
public final class CommentPrompts {
	private CommentPrompts() {}

	private record Pending(BackupType type, String world, long created, Consumer<String> done) {}

	private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
	private static volatile boolean open = false;

	/** Any thread. {@code done} receives text ("" = none) or null (skip). It runs on the game thread. */
	public static void ask(BackupType type, String world, Consumer<String> done) {
		synchronized (QUEUE) { QUEUE.add(new Pending(type, world, System.currentTimeMillis(), done)); }
	}

	/** True while a window is open or waiting: automatic backups are not started meanwhile. */
	public static boolean busy() { synchronized (QUEUE) { return open || !QUEUE.isEmpty(); } }

	/** Game thread, every tick. */
	public static void tick(Minecraft mc) {
		Pending p;
		synchronized (QUEUE) { if (open || QUEUE.isEmpty()) return; p = QUEUE.peek(); }
		boolean leave = p.type() == BackupType.SESSION_LEAVE;
		boolean inWorld = ClientBridge.core.inWorld();
		Screen cur = mc.gui.screen();
		long age = System.currentTimeMillis() - p.created();

		if (!leave && !inWorld) { resolve(p, null); return; }                       // the world is gone: nothing left to back up
		if (leave && age > 45_000) { resolve(p, ""); return; }                       // never found a good moment: back up without a comment
		boolean ready = leave ? (!inWorld && (cur instanceof TitleScreen || isWorldList(cur)))
			: p.type() == BackupType.MANUAL ? true                                     // you asked for it: show it on top of whatever is open
			: (cur == null || cur instanceof PauseScreen);
		if (!ready) return;
		synchronized (QUEUE) { QUEUE.poll(); open = true; }
		mc.gui.setScreen(new CommentScreen(cur, p.type(), p.world(), c -> { open = false; p.done().accept(c); }));
	}

	private static boolean isWorldList(Screen s) { return s != null && s.getClass().getName().endsWith("SelectWorldScreen"); }

	private static void resolve(Pending p, String answer) {
		synchronized (QUEUE) { QUEUE.remove(p); }
		p.done().accept(answer);
	}

	/** The game is closing: answer everything that is waiting with "no comment" so those backups still start. */
	public static void flushAll() {
		Pending p;
		while (true) {
			synchronized (QUEUE) { p = QUEUE.poll(); }
			if (p == null) return;
			try { p.done().accept(p.type() == BackupType.SESSION_LEAVE ? "" : null); } catch (Throwable ignored) { /* shutting down */ }
		}
	}
}
