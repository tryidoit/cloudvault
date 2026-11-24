package com.cloudvault.sync;

import com.cloudvault.util.FileUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cloud sync file names: sync-2026-10-11_09-30-00_PDT_My World_ecc204ec.zip (date, time, zone, world, first 8 characters of the sync id). */
public final class SyncFileName {
	private SyncFileName() {}

	public record Parsed(String date, String time, String zone, String worldName, String id8, boolean encrypted) {
		public long approxMillis() {
			try {
				String[] d = date.split("-"), t = time.split("-");
				return LocalDateTime.of(Integer.parseInt(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2]), Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]))
					.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			} catch (RuntimeException e) { return 0; }
		}
	}

	private static final Pattern NAME = Pattern.compile("^sync-(\\d{4}-\\d\\d-\\d\\d)_(\\d\\d-\\d\\d-\\d\\d)_([A-Za-z0-9+-]+)_(.+)_([0-9a-f]{8})\\.zip(\\.cvenc)?$");
	private static final Pattern FOLDER = Pattern.compile("^(.*) \\[([0-9a-f]{8})\\]$");

	public static String id8(String syncId) { return syncId.substring(0, 8).toLowerCase(Locale.ROOT); }

	/** Cloud folder of one synced world: "My World [ecc204ec]". */
	public static String folder(String worldName, String syncId) { return FileUtil.safeName(worldName) + " [" + id8(syncId) + "]"; }

	/** World name from a folder name made by {@link #folder}, or null. */
	public static String worldOfFolder(String folder) {
		if (folder == null) return null;
		Matcher m = FOLDER.matcher(folder);
		return m.matches() && !m.group(1).isBlank() ? m.group(1) : null;
	}

	public static String build(long epochMillis, String worldName, String syncId) {
		var t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
		String date = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT));
		String zone = t.format(DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH)).replaceAll("[^A-Za-z0-9+-]", "");
		if (zone.isEmpty()) zone = t.format(DateTimeFormatter.ofPattern("xx", Locale.ROOT));
		String world = FileUtil.safeName(worldName);
		if (world.length() > 40) world = world.substring(0, 40).trim();
		return "sync-" + date + "_" + zone + "_" + world + "_" + id8(syncId) + ".zip";
	}

	public static Parsed parse(String name) {
		if (name == null) return null;
		Matcher m = NAME.matcher(name);
		return m.matches() ? new Parsed(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6) != null) : null;
	}
}
