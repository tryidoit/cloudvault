package com.cloudvault.backup;

import com.cloudvault.util.FileUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of backup files, locally and in the cloud, e.g.
 * <pre>backup-2026-10-06_15-10-34_PDT_JOIN_My World_ecc204ec.zip</pre>
 * = date, time, time zone, type, world name, first 8 characters of the backup id. Encrypted copies end in ".zip.cvenc".
 * Older names (backup-date_time_zone-id.zip and backup-uuid.zip) are still understood.
 */
public final class CloudFileName {
	private CloudFileName() {}

	public record Parsed(String date, String time, String zone, BackupType type, String worldName, String id8, boolean encrypted, boolean delta) {
		/** "2026-10-06  15:10:34 PDT" */
		public String when() { return date + "  " + time.replace('-', ':') + (zone.isEmpty() ? "" : " " + zone); }
		/** Best-effort instant (the zone is only an abbreviation, so the computer's own zone is assumed). */
		public long approxMillis() {
			try {
				String[] d = date.split("-"), t = time.split("-");
				return LocalDateTime.of(Integer.parseInt(d[0]), Integer.parseInt(d[1]), Integer.parseInt(d[2]), Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]))
					.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			} catch (RuntimeException e) { return 0; }
		}
	}

	private static final Pattern CURRENT = Pattern.compile(
		"^backup-(\\d{4}-\\d\\d-\\d\\d)_(\\d\\d-\\d\\d-\\d\\d)_([A-Za-z0-9+-]+)_(MANUAL|JOIN|LEAVE|GAMEPLAY|PERIODIC|EVENT|SAFETY|BEFORE-RESET|BEFORE-UPDATE)_(.+)_([0-9a-f]{8})(\\.delta)?\\.zip(\\.cvenc)?$");
	private static final Pattern LEGACY_TIME = Pattern.compile("^backup-(\\d{4}-\\d\\d-\\d\\d)_(\\d\\d-\\d\\d-\\d\\d)_([A-Za-z0-9+-]+)-([0-9a-f]{8})\\.zip(\\.cvenc)?$");
	private static final Pattern LEGACY_ID = Pattern.compile("^backup-([0-9a-fA-F]{8})-[0-9a-fA-F-]{27}\\.zip(\\.cvenc)?$");

	public static String tag(BackupType t) {
		return switch (t) {
			case MANUAL -> "MANUAL"; case SESSION_JOIN -> "JOIN"; case SESSION_LEAVE -> "LEAVE";
			case GAMEPLAY -> "GAMEPLAY"; case PERIODIC -> "PERIODIC"; case EVENT -> "EVENT"; case SAFETY -> "SAFETY"; case PRE_RESET -> "BEFORE-RESET"; case PRE_UPDATE -> "BEFORE-UPDATE";
		};
	}

	static BackupType fromTag(String tag) {
		for (BackupType t : BackupType.values()) if (tag(t).equals(tag)) return t;
		return null;
	}

	public static String build(long epochMillis, BackupType type, String worldName, String backupId) { return build(epochMillis, type, worldName, backupId, false); }

	public static String build(long epochMillis, BackupType type, String worldName, String backupId, boolean delta) {
		var t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
		String date = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT));
		String zone = t.format(DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH)).replaceAll("[^A-Za-z0-9+-]", "");
		if (zone.isEmpty()) zone = t.format(DateTimeFormatter.ofPattern("xx", Locale.ROOT));
		String world = FileUtil.safeName(worldName);
		if (world.length() > 40) world = world.substring(0, 40).trim();
		return "backup-" + date + "_" + zone + "_" + tag(type) + "_" + world + "_" + backupId.substring(0, 8) + (delta ? ".delta" : "") + ".zip";
	}

	/** Null when the name is not a CloudVault backup. Unknown parts (old names) are null/empty rather than guessed. */
	public static Parsed parse(String name) {
		if (name == null) return null;
		Matcher m = CURRENT.matcher(name);
		if (m.matches()) return new Parsed(m.group(1), m.group(2), m.group(3), fromTag(m.group(4)), m.group(5), m.group(6), m.group(8) != null, m.group(7) != null);
		m = LEGACY_TIME.matcher(name);
		if (m.matches()) return new Parsed(m.group(1), m.group(2), m.group(3), null, null, m.group(4), m.group(5) != null, false);
		m = LEGACY_ID.matcher(name);
		if (m.matches()) return new Parsed("", "", "", null, null, m.group(1), m.group(2) != null, false);
		return null;
	}
}
