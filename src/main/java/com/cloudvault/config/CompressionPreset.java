package com.cloudvault.config;

/** Speed/size trade-off for the ZIP step (Deflater level). Fast = least CPU and time, Maximum = smallest file. */
public enum CompressionPreset {
	FAST("Fast", 1, "Quickest backups, lowest CPU use, larger files"),
	BALANCED("Balanced", 6, "Good size for moderate CPU use (default)"),
	MAXIMUM("Maximum", 9, "Smallest files, slowest, highest CPU use");

	public final String label, description;
	public final int level;
	CompressionPreset(String label, int level, String description) { this.label = label; this.level = level; this.description = description; }

	/** The preset for a stored level, or null when it is a custom level. */
	public static CompressionPreset forLevel(int level) {
		for (CompressionPreset p : values()) if (p.level == level) return p;
		return null;
	}
	public CompressionPreset next() { return values()[(ordinal() + 1) % values().length]; }
}
