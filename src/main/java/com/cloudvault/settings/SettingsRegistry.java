package com.cloudvault.settings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Every ordinary setting, described once: label, tip, page and kind. The settings screens render pages from this list and the Advanced
 * wizard walks every page, so a setting registered here appears in both. A reflection test (Milestone8Test) fails when a public field of
 * the config is neither registered nor on the explicit {@link #IGNORED} list.
 *
 * Paths are relative to a root object: root CONFIG = CloudVaultConfig ("upload.paused"), root BACKUP = BackupSettings ("session.joinEnabled"),
 * which the screens bind to the global settings or to one world's override.
 */
public final class SettingsRegistry {
	public enum Kind { TOGGLE, INT, CHOICE, TEXT }
	public enum Root { CONFIG, BACKUP }

	public static final class Setting {
		public final Root root; public final String path, label, tip; public final String[] page; public Kind kind;
		public int min = Integer.MIN_VALUE, max = Integer.MAX_VALUE; public String unit = ""; public List<String> choices = List.of();
		/** Hidden from the quick wizard but present in the advanced wizard and in the settings pages (always true: kept for the wizard). */
		public boolean advancedOnly;
		Setting(Root root, String[] page, String path, String label, String tip) { this.root = root; this.page = page; this.path = path; this.label = label; this.tip = tip; }
		public Setting range(int min, int max) { this.min = min; this.max = max; return this; }
		public Setting unit(String u) { this.unit = u; return this; }
		public Setting adv() { this.advancedOnly = true; return this; }
		public String id() { return root + ":" + path; }

		public Object get(Object rootObject) { return read(rootObject, path); }
		public String getText(Object rootObject) { Object v = get(rootObject); return v == null ? "" : v.toString(); }
		/** Validates and stores. Returns false when the value is not acceptable (nothing changes). */
		public boolean set(Object rootObject, Object value) {
			try {
				Object v = value;
				if (kind == Kind.INT) { int i = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim()); v = Math.max(min, Math.min(max, i)); }
				else if (kind == Kind.TOGGLE) v = value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
				else if (kind == Kind.CHOICE) { String s = value.toString(); if (!choices.contains(s)) return false; v = s; }
				else v = value == null ? "" : value.toString();
				write(rootObject, path, v);
				return true;
			} catch (RuntimeException e) { return false; }
		}
	}

	public static final class Page {
		public final String[] path; public final List<Setting> settings = new ArrayList<>();
		/** A page with its own screen (lists, logins, secrets); the generic renderer shows its settings, then a button to the custom screen. */
		public String custom = null;
		Page(String[] path) { this.path = path; }
		public String title() { return path[path.length - 1]; }
		public String key() { return String.join("/", path); }
	}

	/** Public fields that are intentionally not settings (state, identity, lists with their own screens). Paths relative to CloudVaultConfig. */
	public static final Set<String> IGNORED = Set.of("firstLaunchDone", "autoPausedUntil", "worlds", "account.deviceId", "account.importedFrom", "account.lastUploadAt", "alerts.channels");

	private final List<Setting> all = new ArrayList<>();
	private final LinkedHashMap<String, Page> pages = new LinkedHashMap<>();

	public List<Setting> all() { return Collections.unmodifiableList(all); }
	public Collection<Page> pages() { return Collections.unmodifiableCollection(pages.values()); }
	public Page page(String key) { return pages.get(key); }
	public List<Setting> forRoot(Root r) { List<Setting> l = new ArrayList<>(); for (Setting s : all) if (s.root == r) l.add(s); return l; }

	private Page pg(String... path) { return pages.computeIfAbsent(String.join("/", path), k -> new Page(path)); }
	private Setting add(Root r, String[] page, String path, String label, String tip, Kind k, String... choices) {
		Setting s = new Setting(r, page, path, label, tip); s.kind = k; if (choices.length > 0) s.choices = List.of(choices);
		all.add(s); pg(page).settings.add(s); return s;
	}
	private Setting t(Root r, String[] p, String path, String label, String tip) { return add(r, p, path, label, tip, Kind.TOGGLE); }
	private Setting n(Root r, String[] p, String path, String label, String tip) { return add(r, p, path, label, tip, Kind.INT); }
	private static final String[] UNITS = {"MINUTES", "HOURS", "DAYS", "WEEKS", "MONTHS", "YEARS"};

	public SettingsRegistry() {
		Root B = Root.BACKUP, C = Root.CONFIG;
		String[] when = {"Backups", "When"}, keep = {"Backups", "Keeping"}, comm = {"Backups", "Comments"};
		t(B, when, "session.joinEnabled", "Back up when joining a world", "A backup is made as you open a world.");
		t(B, when, "session.leaveEnabled", "Back up when leaving a world", "A backup is made as you leave a world.");
		t(B, when, "periodic.enabled", "Back up on a timer", "A backup every so often while a world is open.");
		n(B, when, "periodic.amount", "Timer: every", "How many units between timed backups.").range(1, 9999);
		add(B, when, "periodic.unit", "Timer: unit", "The unit for the timer.", Kind.CHOICE, UNITS);
		t(B, when, "periodic.avoidDuplicates", "Timer: skip when nothing changed", "No backup if the world is the same as the last one.");
		t(B, when, "gameplay.enabled", "Back up after playing for a while", "A backup after this much uninterrupted play time.");
		n(B, when, "gameplay.amount", "Play time: after", "How many units of play.").range(1, 9999);
		add(B, when, "gameplay.unit", "Play time: unit", "The unit for play time.", Kind.CHOICE, "MINUTES", "HOURS", "DAYS", "WEEKS");
		t(B, when, "manual.confirmBeforeBackup", "Ask before a manual backup", "Shows a confirmation when you press the backup button.");
		t(B, when, "manual.avoidDuplicates", "Manual: skip when nothing changed", "Normally a manual backup is always made.").adv();
		t(B, when, "smart.enabled", "Smart triggers", "Extra backups at moments that matter.");
		t(B, when, "smart.enterNether", "Smart: entering the Nether", "Back up as you enter the Nether.");
		t(B, when, "smart.enterEnd", "Smart: entering the End", "Back up as you enter the End.");
		t(B, when, "smart.lowHealth", "Smart: low health", "Back up when health gets low (once per situation).");
		n(B, when, "smart.lowHealthHearts", "Low health means", "Hearts or fewer.").range(1, 9).unit("hearts");
		t(B, when, "smart.idle", "Smart: standing idle", "Back up when you stand still for a while.");
		n(B, when, "smart.idleMinutes", "Idle means", "Standing still this long.").range(1, 60).unit("min");
		n(B, when, "smart.cooldownMinutes", "Smart: at most once every", "Smart backups never come more often than this.").range(1, 240).unit("min");

		n(B, keep, "session.retention", "Keep (join/leave)", "How many join/leave backups to keep (-1 = all, 0 = newest only).").range(-1, 100);
		n(B, keep, "periodic.retention", "Keep (timer)", "How many timed backups to keep (-1 = all).").range(-1, 100);
		n(B, keep, "gameplay.retention", "Keep (play time)", "How many play-time backups to keep (-1 = all).").range(-1, 100);
		n(B, keep, "manual.retention", "Keep (manual)", "How many manual backups to keep (-1 = all).").range(-1, 100);
		n(B, keep, "smart.retention", "Keep (smart)", "How many smart backups to keep (-1 = all).").range(-1, 100);
		t(B, keep, "advanced.enabled", "Combined retention rules", "Replaces the numbers above with newest / daily / weekly / monthly rules (turns incremental off).").adv();
		n(B, keep, "advanced.keepNewest", "Rules: newest", "Always keep this many of the newest.").range(0, 100).adv();
		n(B, keep, "advanced.dailyDays", "Rules: one per day for", "Days.").range(0, 365).adv();
		n(B, keep, "advanced.weeklyWeeks", "Rules: one per week for", "Weeks.").range(0, 260).adv();
		n(B, keep, "advanced.monthlyMonths", "Rules: one per month for", "Months.").range(0, 120).adv();
		t(B, keep, "incremental.enabled", "Incremental backups", "Later backups store only what changed. Never uses more space than full backups.");
		n(B, keep, "incremental.maxChain", "Incremental: at most", "Incremental backups in a row after a full one.").range(1, 20).adv();
		n(B, keep, "incremental.fullEveryDays", "Incremental: a full backup every", "At least this often.").range(1, 90).unit("days").adv();
		n(B, keep, "incremental.maxChangedPercent", "Incremental: full when more changed than", "Percent of the world.").range(10, 90).unit("%").adv();

		t(B, comm, "session.joinComment", "Ask for a comment: join", "Shows a box to type a note.");
		t(B, comm, "session.leaveComment", "Ask for a comment: leave", "Shows a box to type a note.");
		t(B, comm, "periodic.comment", "Ask for a comment: timer", "Shows a box to type a note.");
		t(B, comm, "gameplay.comment", "Ask for a comment: play time", "Shows a box to type a note.");
		t(B, comm, "manual.comment", "Ask for a comment: manual", "Shows a box to type a note.");
		t(B, comm, "smart.comment", "Ask for a comment: smart", "Shows a box to type a note.");

		String[] store = {"Cloud", "Storage"}, second = {"Cloud", "Second destination"}, sync = {"Cloud", "Cloud Sync"}, upl = {"Cloud", "Upload controls"}, enc = {"Cloud", "Encryption"};
		t(C, store, "cloudEnabled", "Upload to the cloud", "Off = backups stay on this computer.");
		add(C, store, "cloudProvider", "Cloud service", "Where backups go.", Kind.CHOICE, "google_drive", "s3");
		add(C, store, "s3Endpoint", "S3 address", "Endpoint of your S3-compatible storage.", Kind.TEXT);
		add(C, store, "s3Region", "S3 region", "Often 'auto'.", Kind.TEXT);
		add(C, store, "s3Bucket", "S3 bucket", "Name of the bucket.", Kind.TEXT);
		add(C, store, "s3AccessKey", "S3 access key", "The secret key is kept separately in the secret store.", Kind.TEXT);
		t(C, store, "s3PathStyle", "S3 path-style addresses", "Needed by most non-Amazon services.").adv();
		t(C, store, "retryFailedUploads", "Retry failed uploads", "Automatically try again later.");
		n(C, store, "retryDelaySeconds", "Retry delay", "Seconds before the first retry.").range(5, 86400).unit("s").adv();
		n(C, store, "compressionLevel", "Compression", "0 = fastest, 9 = smallest.").range(0, 9).adv();
		n(C, store, "localCacheKeep", "Copies kept on this computer", "0 = none (cloud only).").range(0, 100);

		t(C, second, "mirror.enabled", "Second destination", "Every backup is also copied somewhere else.");
		add(C, second, "mirror.type", "Second destination type", "A folder, S3 or a second Google account.", Kind.CHOICE, "local", "s3", "google_drive");
		add(C, second, "mirror.localPath", "Folder", "A NAS, USB drive or a Dropbox / OneDrive folder.", Kind.TEXT);
		add(C, second, "mirror.s3Endpoint", "S3 address", "Endpoint of the second S3 storage.", Kind.TEXT);
		add(C, second, "mirror.s3Region", "S3 region", "Often 'auto'.", Kind.TEXT);
		add(C, second, "mirror.s3Bucket", "S3 bucket", "Name of the bucket.", Kind.TEXT);
		add(C, second, "mirror.s3AccessKey", "S3 access key", "The secret key is kept separately.", Kind.TEXT);
		t(C, second, "mirror.s3PathStyle", "S3 path-style addresses", "Needed by most non-Amazon services.").adv();

		t(C, sync, "sync.newWorldsSync", "Sync new worlds", "New worlds are synced between your computers by default.");
		n(C, sync, "sync.keep", "Sync copies kept", "Older copies in the cloud.").range(1, 20);
		t(C, sync, "sync.autoDownload", "Download newer worlds automatically", "When another computer has a newer copy.");
		t(C, sync, "lock.enabled", "Warn when a world is open elsewhere", "Prevents two computers from changing one world at once.");
		n(C, sync, "lock.staleMinutes", "Treat a lock as old after", "Minutes without a sign of life.").range(2, 120).unit("min").adv();

		t(C, upl, "upload.paused", "Pause all uploads", "Backups are still made here; they go up when you resume.");
		t(C, upl, "upload.onlyWhenNotPlaying", "Only upload when not playing", "Uploads start after you leave the world.");
		n(C, upl, "upload.speedLimitKBps", "Speed limit", "0 = no limit.").range(0, 1_000_000).unit("KB/s");
		t(C, upl, "upload.windowEnabled", "Only upload during certain hours", "Local time.");
		n(C, upl, "upload.windowStartHour", "Hours: from", "0-23.").range(0, 23);
		n(C, upl, "upload.windowEndHour", "Hours: until", "0-23; earlier than 'from' runs over midnight.").range(0, 23);

		t(C, enc, "encryptionEnabled", "Encrypt backups", "Needs a key (set up on the Encryption screen). Lose the key = lose the backups.");

		String[] al = {"Alerts"};
		t(C, al, "alerts.enabled", "Send alerts outside the game", "Phone, chat or e-mail when something goes wrong.");
		t(C, al, "alerts.onBackupFailed", "Alert: a backup failed", "").adv();
		t(C, al, "alerts.onUploadFailed", "Alert: an upload failed", "").adv();
		t(C, al, "alerts.onNoRecentBackup", "Alert: no recent backup", "").adv();
		n(C, al, "alerts.noBackupDays", "No recent backup means", "Days without a successful backup (0 = off).").range(0, 365).unit("days").adv();
		t(C, al, "alerts.onLowSpace", "Alert: low disk space", "").adv();
		t(C, al, "alerts.onSyncProblem", "Alert: sync problem", "").adv();
		t(C, al, "alerts.onRestoreTestFailed", "Alert: restore test failed", "").adv();
		t(C, al, "alerts.onWorldTakeover", "Alert: world opened elsewhere", "").adv();
		n(C, al, "alerts.minMinutesBetween", "Same alert at most every", "Minutes.").range(1, 1440).unit("min").adv();
		pg("Alerts").custom = "alert-channels";
		pg("Cloud", "Storage").custom = "cloud"; pg("Cloud", "Second destination").custom = "mirror"; pg("Cloud", "Encryption").custom = "encryption"; pg("Advanced").custom = "tools";

		String[] wo = {"Worlds"};
		t(C, wo, "newWorldsEnabled", "Back up new worlds", "Worlds you have not switched on or off follow this.");
		t(C, wo, "showWorldIndicator", "Show CloudVault: ON/OFF", "A small indicator while playing.");
		pg("Worlds").custom = "world-list";

		String[] ac = {"Account & Devices"};
		t(C, ac, "account.enabled", "CloudVault account", "Keeps your settings in your cloud so a new computer can set itself up.");
		add(C, ac, "account.deviceName", "This computer's name", "Shown in the settings file name and on other computers.", Kind.TEXT);
		pg("Account & Devices").custom = "account";

		String[] adv = {"Advanced"};
		t(C, adv, "enabled", "CloudVault on", "Master switch.");
		t(C, adv, "notifications", "Show notifications", "Toasts in the game.");
		t(C, adv, "backupBeforeUpdate", "Safety backup before updates", "When Minecraft or mods changed since last time.");
		n(C, adv, "restoreTestDays", "Test restoring every", "Quietly tests the newest backup (0 = never).").range(0, 365).unit("days");
	}

	// ---------------------------------------------------------------- path access

	static Object read(Object root, String path) {
		try {
			Object o = root;
			for (String p : path.split("\\.")) o = o.getClass().getField(p).get(o);
			return o instanceof Enum<?> e ? e.name() : o;
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Bad setting path " + path, e); }
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static void write(Object root, String path, Object value) {
		try {
			String[] ps = path.split("\\."); Object o = root;
			for (int i = 0; i < ps.length - 1; i++) o = o.getClass().getField(ps[i]).get(o);
			Field f = o.getClass().getField(ps[ps.length - 1]);
			Class<?> ft = f.getType();
			if (ft.isEnum()) f.set(o, Enum.valueOf((Class<Enum>) ft, value.toString()));
			else if (ft == int.class) f.setInt(o, ((Number) value).intValue());
			else if (ft == boolean.class) f.setBoolean(o, (Boolean) value);
			else f.set(o, value);
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Bad setting path " + path, e); }
	}

	/** Every leaf field (int, boolean, String, enum) of the config as a path; for the coverage test. */
	public static Set<String> leafPaths(Class<?> type, String prefix) {
		Set<String> out = new TreeSet<>();
		for (Field f : type.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			Class<?> ft = f.getType(); String p = prefix + f.getName();
			if (ft.isPrimitive() || ft == String.class || ft.isEnum() || ft == Boolean.class || ft == Integer.class || Map.class.isAssignableFrom(ft) || List.class.isAssignableFrom(ft)) out.add(p);
			else out.addAll(leafPaths(ft, p + "."));
		}
		return out;
	}
}
