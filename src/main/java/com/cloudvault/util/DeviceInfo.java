package com.cloudvault.util;

import java.util.Locale;

/** Which computer this is, in words a person recognises (used in cloud file names and in the "world in use" message). */
public final class DeviceInfo {
	private DeviceInfo() {}

	public static String hostName() {
		String h = System.getenv("COMPUTERNAME");
		if (h == null || h.isBlank()) h = System.getenv("HOSTNAME");
		if (h == null || h.isBlank()) { try { h = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = "computer"; } }
		return h.replaceAll("\\p{Cntrl}", "").trim();
	}

	public static String os() {
		String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return n.contains("win") ? "Windows" : n.contains("mac") ? "macOS" : n.contains("linux") ? "Linux" : System.getProperty("os.name", "OS");
	}

	/** Name shown to people: the chosen name, else the computer name. */
	public static String displayName(String chosen) { return chosen != null && !chosen.isBlank() ? chosen.trim() : hostName(); }

	/** One line, e.g. "DESKTOP-4F2 (Windows, Minecraft 26.3)". */
	public static String describe(String chosen, String mcVersion) {
		return displayName(chosen) + " (" + os() + (mcVersion == null || mcVersion.isBlank() ? "" : ", Minecraft " + mcVersion) + ")";
	}
}
