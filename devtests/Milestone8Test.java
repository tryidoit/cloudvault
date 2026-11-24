import com.cloudvault.config.*;
import com.cloudvault.settings.SettingsRegistry;
import java.util.*;

/** The settings registry covers every config field, reads/writes correctly and validates. */
public class Milestone8Test {
	static int failures = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) failures++; }

	public static void main(String[] a) {
		SettingsRegistry reg = new SettingsRegistry();
		System.out.println("Coverage");
		Set<String> cfg = SettingsRegistry.leafPaths(CloudVaultConfig.class, "");
		Set<String> registeredConfig = new TreeSet<>(), registeredBackup = new TreeSet<>();
		for (var s : reg.all()) (s.root == SettingsRegistry.Root.CONFIG ? registeredConfig : registeredBackup).add(s.path);
		List<String> missing = new ArrayList<>();
		for (String p : cfg) {
			if (SettingsRegistry.IGNORED.contains(p)) continue;
			if (p.startsWith("global.")) { if (!registeredBackup.contains(p.substring(7))) missing.add(p); }
			else if (!registeredConfig.contains(p)) missing.add(p);
		}
		check(missing.isEmpty(), "every CloudVaultConfig field is a setting or on the ignore list" + (missing.isEmpty() ? "" : " - MISSING " + missing));
		List<String> dead = new ArrayList<>();
		for (String p : registeredConfig) if (!cfg.contains(p)) dead.add(p);
		for (String p : registeredBackup) if (!cfg.contains("global." + p)) dead.add(p);
		check(dead.isEmpty(), "every registered setting points at a real field" + (dead.isEmpty() ? "" : " - " + dead));
		for (String p : SettingsRegistry.IGNORED) if (!cfg.contains(p)) { check(false, "ignore list entry is a real field: " + p); }
		Set<String> perWorld = SettingsRegistry.leafPaths(BackupSettings.class, "");
		check(perWorld.equals(registeredBackup), "BackupSettings (global and per-world) fully registered" + (perWorld.equals(registeredBackup) ? "" : " " + perWorld + " vs " + registeredBackup));
		Set<String> ids = new HashSet<>(); boolean uniq = true; for (var s : reg.all()) uniq &= ids.add(s.id());
		check(uniq, "setting ids are unique");
		check(reg.all().stream().allMatch(s -> !s.label.isBlank()), "every setting has a label");

		System.out.println("Read, write, validate");
		CloudVaultConfig c = new CloudVaultConfig();
		boolean read = true; for (var s : reg.all()) { try { s.get(s.root == SettingsRegistry.Root.CONFIG ? c : c.global); } catch (RuntimeException e) { read = false; System.out.println("   cannot read " + s.id()); } }
		check(read, "every setting can be read");
		var up = reg.all().stream().filter(s -> s.path.equals("upload.speedLimitKBps")).findFirst().get();
		check(up.set(c, "250") && c.upload.speedLimitKBps == 250, "text number stored");
		check(up.set(c, 99_999_999) && c.upload.speedLimitKBps == 1_000_000, "out-of-range number is clamped");
		check(!up.set(c, "abc") && c.upload.speedLimitKBps == 1_000_000, "garbage number is refused, value unchanged");
		var unit = reg.all().stream().filter(s -> s.path.equals("periodic.unit")).findFirst().get();
		check(unit.set(c.global, "DAYS") && c.global.periodic.unit.name().equals("DAYS") && unit.get(c.global).equals("DAYS"), "enum choice stored and read as text");
		check(!unit.set(c.global, "FORTNIGHTS"), "unknown choice refused");
		var tg = reg.all().stream().filter(s -> s.path.equals("smart.idle")).findFirst().get();
		check(tg.set(c.global, true) && c.global.smart.idle && tg.set(c.global, "false") && !c.global.smart.idle, "toggle");
		var wo = new WorldSettings(); wo.settings = new BackupSettings();
		tg.set(wo.settings, true);
		check(wo.settings.smart.idle && !c.global.smart.idle, "the same setting binds to a per-world override separately");
		var txt = reg.all().stream().filter(s -> s.path.equals("mirror.localPath")).findFirst().get();
		check(txt.set(c, "D:\\Backups") && c.mirror.localPath.equals("D:\\Backups"), "text stored");

		System.out.println("Pages");
		check(reg.page("Backups/When") != null && reg.page("Cloud/Upload controls") != null && reg.page("Alerts").custom != null, "main pages exist; Alerts has its own channel screen");
		check(reg.pages().stream().allMatch(p -> !p.settings.isEmpty() || p.custom != null), "no empty page");
		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
