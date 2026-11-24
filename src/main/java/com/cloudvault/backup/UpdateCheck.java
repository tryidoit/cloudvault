package com.cloudvault.backup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.TreeSet;

/** Decides when a world needs a safety backup before it is opened after an update. Pure logic. */
public final class UpdateCheck {
	private UpdateCheck() {}

	/**
	 * @param levelVersion  Minecraft version the world was last saved with (null if unknown: no decision from it)
	 * @param currentMc     Minecraft version now
	 * @param lastSeenMc    version the previous check recorded for this world ("" = never checked)
	 * @param lastSeenMods  mod-set hash the previous check recorded ("" = never checked)
	 * @return the reason to back up, or null. Each change triggers once: the caller then records the current values.
	 */
	public static String reason(String levelVersion, String currentMc, String lastSeenMc, String lastSeenMods, String currentMods) {
		if (levelVersion != null && !levelVersion.equals(currentMc) && !currentMc.equals(lastSeenMc))
			return "Minecraft changed from " + levelVersion + " to " + currentMc;
		if (!lastSeenMods.isEmpty() && !lastSeenMods.equals(currentMods)) return "your mods changed";
		return null;
	}

	/** Stable hash of "id@version" for every mod. */
	public static String modsHash(Collection<String> idAtVersion) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (String s : new TreeSet<>(idAtVersion)) { md.update(s.getBytes(StandardCharsets.UTF_8)); md.update((byte) '\n'); }
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.substring(0, 32);
		} catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
