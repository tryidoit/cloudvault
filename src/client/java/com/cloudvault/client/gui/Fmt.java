package com.cloudvault.client.gui;

import com.cloudvault.backup.BackupMetadata;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/** Small text helpers shared by the screens. */
final class Fmt {
	private Fmt() {}
	static String size(long b) {
		if (b < 1024) return b + " B";
		double v = b / 1024.0; String[] u = {"KB", "MB", "GB", "TB"}; int i = 0;
		while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
		return String.format(v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
	}
	static String date(long ms) { return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())); }
	static String describe(BackupMetadata b) { return date(b.timestamp) + "  " + b.backupType.label() + (b.isDelta() ? " (incr.)" : "") + "  " + size(b.fileSize); }
	static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, Math.max(0, n - 1)) + "..."; }
}
