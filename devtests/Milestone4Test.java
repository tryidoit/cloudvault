import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.*;
import com.cloudvault.config.*;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.restore.*;
import com.cloudvault.util.HashUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Tests for pins/notes, restore tests, before-update backups, space warnings, export. */
public class Milestone4Test {
	static int fails = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

	static class Fake implements CloudProvider {
		final String id; Map<String, byte[]> files = new LinkedHashMap<>(); Map<String, String> names = new HashMap<>(); int n = 0; int quotaPct = -1;
		Fake(String id) { this.id = id; }
		public String id() { return id; } public boolean isAuthenticated() { return true; }
		public void authenticate() {} public void signOut() {}
		public Status testConnection() { return new Status(true, "fake", "ok"); }
		public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException {
			byte[] b = Files.readAllBytes(f); String rid = id + (++n); files.put(rid, b); names.put(rid, name);
			return new RemoteFile(rid, name, b.length, HashUtil.hashFile(f).md5(), props);
		}
		public void downloadBackup(String rid, Path t) throws IOException { Files.write(t, files.get(rid)); }
		public List<RemoteFile> listBackups(String w) { return List.of(); }
		public void deleteBackup(String rid) { files.remove(rid); }
		public StorageInfo storageInfo() { return quotaPct < 0 ? null : new StorageInfo(quotaPct * 1_000_000L, 100_000_000L); }
	}

	static byte[] nbt(String version) throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(bo))) {
			d.writeByte(10); d.writeUTF("");
			d.writeByte(10); d.writeUTF("Data");
			if (version != null) { d.writeByte(10); d.writeUTF("Version"); d.writeByte(8); d.writeUTF("Name"); d.writeUTF(version); d.writeByte(3); d.writeUTF("Id"); d.writeInt(4000); d.writeByte(1); d.writeUTF("Snapshot"); d.writeByte(0); d.writeByte(0); }
			d.writeByte(8); d.writeUTF("LevelName"); d.writeUTF("W\u00f6rld");
			d.writeByte(7); d.writeUTF("Bytes"); d.writeInt(3); d.write(new byte[]{1, 2, 3});
			d.writeByte(11); d.writeUTF("Ints"); d.writeInt(2); d.writeInt(1); d.writeInt(2);
			d.writeByte(12); d.writeUTF("Longs"); d.writeInt(1); d.writeLong(7);
			d.writeByte(9); d.writeUTF("List"); d.writeByte(3); d.writeInt(2); d.writeInt(1); d.writeInt(2);
			d.writeByte(9); d.writeUTF("Empty"); d.writeByte(0); d.writeInt(0);
			d.writeByte(0); d.writeByte(0);
		}
		return bo.toByteArray();
	}

	static Path world(Path saves, String name, String data, boolean validLevel, boolean badRegion) throws IOException {
		Path w = saves.resolve(name); Files.createDirectories(w.resolve("region"));
		if (validLevel) Files.write(w.resolve("level.dat"), nbt("1.21.4"));
		Files.write(w.resolve("region/r.0.0.mca"), badRegion ? new byte[5000] : new byte[8192 + 4096]);
		Files.writeString(w.resolve("data.txt"), data); return w;
	}

	/** Puts a ready-made ZIP into the store as a local backup (to test archives the normal backup path would never produce). */
	static BackupMetadata inject(CloudVaultCore c, String wid, String name, Map<String, byte[]> entries) throws Exception {
		BackupMetadata m = new BackupMetadata(); m.worldId = wid; m.worldName = name; m.backupType = BackupType.MANUAL; m.timestamp = System.currentTimeMillis(); m.minecraftVersion = "1.21.4";
		m.zipFileName = CloudFileName.build(m.timestamp, m.backupType, name, m.backupId);
		Path z = c.store.zipPath(m); Files.createDirectories(z.getParent());
		try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) { for (var e : entries.entrySet()) { zo.putNextEntry(new ZipEntry(e.getKey())); zo.write(e.getValue()); zo.closeEntry(); } }
		var h = HashUtil.hashFile(z); m.sha256 = h.sha256(); m.md5 = h.md5(); m.fileSize = h.size(); m.localVerified = true; m.uploadStatus = UploadStatus.LOCAL_ONLY; c.store.save(m); return m;
	}

	static BackupMetadata meta(CloudVaultCore c, String wid, long ts, boolean pinned) throws IOException {
		BackupMetadata m = new BackupMetadata(); m.worldId = wid; m.worldName = "W"; m.backupType = BackupType.PERIODIC; m.timestamp = ts;
		m.localVerified = true; m.uploadStatus = UploadStatus.LOCAL_ONLY; m.localFilePresent = false; m.sha256 = "x"; m.pinned = pinned; c.store.save(m); return m;
	}

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cvm4");
		List<String> notes = Collections.synchronizedList(new ArrayList<>());
		NotificationSink sink = (lv, t, m) -> notes.add(lv + ": " + m);

		// ---- 1. pinned backups ----
		CloudVaultCore c = new CloudVaultCore(tmp.resolve("cv1"), sink);
		long now = System.currentTimeMillis(), H = 3_600_000L;
		List<BackupMetadata> all = new ArrayList<>();
		for (int i = 0; i < 8; i++) all.add(meta(c, "pin-world", now - i * H, i == 7)); // oldest is pinned
		c.retention.apply("pin-world", BackupType.Category.PERIODIC, 1, null, null);
		long left = c.store.forWorld("pin-world").size();
		check(left == 3 && c.store.find(all.get(7).backupId).isPresent(), "simple retention (newest + 1): pinned oldest backup survives and does not use a slot (" + left + " left)");
		for (int i = 0; i < 20; i++) meta(c, "pin2", now - i * H, i == 19);
		var pol = new RetentionPolicy(); pol.enabled = true; pol.keepNewest = 2; pol.dailyDays = 0; pol.weeklyWeeks = 0; pol.monthlyMonths = 0;
		c.retention.apply("pin2", BackupType.Category.PERIODIC, 1, pol, null);
		check(c.store.forWorld("pin2").size() == 3 && c.store.forWorld("pin2").stream().anyMatch(x -> x.pinned), "advanced retention also leaves pinned backups alone");
		c.setPinned(all.get(7).backupId, false); c.retention.apply("pin-world", BackupType.Category.PERIODIC, 1, null, null);
		check(c.store.find(all.get(7).backupId).isEmpty(), "after unpinning, retention can remove it again");
		check(BackupFilter.apply(c.store.forWorld("pin2"), BackupFilter.Filter.PINNED, BackupFilter.Sort.NEWEST).size() == 1, "Pinned filter");

		// ---- 2. notes ----
		check(CloudVaultCore.cleanNote("  before the \u0000dragon\nfight  ").equals("before the  dragon fight") && CloudVaultCore.cleanNote("x".repeat(200)).length() == 80 && CloudVaultCore.cleanNote(null).isEmpty(), "notes: control characters removed, length limited");
		BackupMetadata nm = c.store.forWorld("pin2").get(0);
		c.setNote(nm.backupId, "my note"); check("my note".equals(c.store.find(nm.backupId).get().note), "note saved");
		c.setNote(nm.backupId, "  "); check(c.store.find(nm.backupId).get().note == null && !c.store.update("no-such-id", m -> {}), "empty note removes it; updating a missing record is harmless");

		// ---- 3. level.dat reader ----
		Path ld = tmp.resolve("level.dat"); Files.write(ld, nbt("1.21.4"));
		check("1.21.4".equals(LevelInfo.readVersion(ld)) && LevelInfo.looksValid(ld), "level.dat: version read from gzip NBT (with every tag type present)");
		Files.write(ld, nbt(null)); check(LevelInfo.readVersion(ld) == null && LevelInfo.looksValid(ld), "level.dat without a version: null, still valid");
		Files.writeString(ld, "not nbt"); check(LevelInfo.readVersion(ld) == null && !LevelInfo.looksValid(ld), "garbage level.dat: null, invalid");
		try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(ld)))) { d.writeByte(10); d.writeUTF(""); for (int i = 0; i < 33; i++) { d.writeByte(10); d.writeUTF("a"); } }
		check(!LevelInfo.looksValid(ld), "absurdly deep NBT is refused");
		try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(ld))) { byte[] z = new byte[1 << 20]; for (int i = 0; i < 20; i++) o.write(z); }
		long t0 = System.currentTimeMillis(); boolean bomb = !LevelInfo.looksValid(ld);
		check(bomb && System.currentTimeMillis() - t0 < 3000, "a gzip bomb as level.dat is refused quickly");

		// ---- 4. update decisions ----
		check(UpdateCheck.reason("1.20.1", "1.21.4", "", "", "h1") != null && UpdateCheck.reason("1.21.4", "1.21.4", "", "", "h1") == null, "update check: older world version triggers, same version does not");
		check(UpdateCheck.reason("1.20.1", "1.21.4", "1.21.4", "", "h1") == null, "update check: triggers once per version (even if names differ slightly)");
		check(UpdateCheck.reason(null, "1.21.4", "", "", "h1") == null && UpdateCheck.reason("1.21.4", "1.21.4", "1.21.4", "", "h1") == null, "update check: unknown world version or first mod sighting does not trigger");
		check("your mods changed".equals(UpdateCheck.reason("1.21.4", "1.21.4", "1.21.4", "h1", "h2")) && UpdateCheck.reason("1.21.4", "1.21.4", "1.21.4", "h1", "h1") == null, "update check: changed mod set triggers");
		check(UpdateCheck.modsHash(List.of("a@1", "b@2")).equals(UpdateCheck.modsHash(List.of("b@2", "a@1"))) && !UpdateCheck.modsHash(List.of("a@1")).equals(UpdateCheck.modsHash(List.of("a@2"))), "mod set hash ignores order, sees version changes");
		check(c.checkUpdate("upd-world", "1.20.1", "1.21.4", "h1") != null && c.checkUpdate("upd-world", "1.20.1", "1.21.4", "h1") == null && c.checkUpdate("upd-world", "1.21.4", "1.21.4", "h2") != null, "core: each change is reported once, then remembered");

		// ---- 5. backup before the world opens: copy now, finish later ----
		Path saves = tmp.resolve("saves");
		Path wDir = world(saves, "Upd World", "BEFORE", true, false);
		String wid = c.worlds.idFor("Upd World");
		var req = new BackupManager.Request(wDir, "Upd World", wid, BackupType.PRE_UPDATE, "1.21.4");
		BackupManager.Work work = c.backups.copyNow(req);
		Files.writeString(wDir.resolve("data.txt"), "AFTER-THE-WORLD-OPENED"); // the world changes after the copy
		c.backups.submitFinish(req, work); c.queue.awaitIdle(20_000);
		BackupMetadata pre = c.store.forWorld(wid).stream().filter(x -> x.backupType == BackupType.PRE_UPDATE).findFirst().orElse(null);
		String frozen = ""; if (pre != null) try (ZipFile zf = new ZipFile(c.store.zipPath(pre).toFile())) { frozen = new String(zf.getInputStream(zf.getEntry("data.txt")).readAllBytes()); }
		check(pre != null && frozen.equals("BEFORE") && pre.backupType.label().equals("BEFORE UPDATE") && pre.fileName().contains("_BEFORE-UPDATE_"), "before-update backup holds the world exactly as it was when copied (label BEFORE UPDATE)");
		check(c.restore.latestUsable(wid) == null && BackupType.PRE_UPDATE.category == BackupType.Category.SAFETY, "it is a safety backup: never auto-deleted and never picked as 'latest'");
		c.setWorldEnabled(wid, "Upd World", false);
		Path missing = saves.resolve("nope"); boolean threw = false; try { c.backups.copyNow(new BackupManager.Request(missing, "x", wid, BackupType.PRE_UPDATE, "1.21.4")); } catch (IOException e) { threw = true; }
		try (var ls = Files.list(tmp.resolve("cv1/temp"))) { check(threw && ls.count() == 0, "failed copy: reported, nothing left in temp"); }
		c.setWorldEnabled(wid, "Upd World", true);

		// ---- 6. restore test ----
		Fake cloud = new Fake("google_drive"); c.setProvider(cloud);
		var good = c.backups.run(new BackupManager.Request(wDir, "Upd World", wid, BackupType.MANUAL, "1.21.4")); c.queue.awaitIdle(10_000);
		String fpBefore = com.cloudvault.util.FileUtil.fingerprint(wDir);
		var r1 = c.runTest(c.store.find(good.backupId).get(), false);
		var after = c.store.find(good.backupId).get();
		check(r1.outcome() == RestoreTester.Outcome.OK && "OK".equals(after.testResult) && after.testedAt > 0, "restore test of a healthy backup: OK, result remembered");
		try (var ls = Files.list(tmp.resolve("cv1/temp"))) { check(ls.noneMatch(f -> f.getFileName().toString().startsWith("restoretest-")) && com.cloudvault.util.FileUtil.fingerprint(wDir).equals(fpBefore), "test leaves no temp files and never touches the world"); }
		String id2 = c.worlds.idFor("NoLevel");
		var b2 = inject(c, id2, "NoLevel", new LinkedHashMap<>(Map.of("region/r.0.0.mca", new byte[8192])));
		var r2 = c.runTest(c.store.find(b2.backupId).get(), false);
		check(r2.outcome() == RestoreTester.Outcome.FAILED && r2.message().contains("level.dat") && notes.stream().anyMatch(n -> n.contains("Make a new backup")), "backup without level.dat: FAILED, player is told");
		Path w3 = world(saves, "BadRegion", "x", true, true); String id3 = c.worlds.idFor("BadRegion");
		var b3 = c.backups.run(new BackupManager.Request(w3, "BadRegion", id3, BackupType.MANUAL, "1.21.4"));
		check(c.runTest(c.store.find(b3.backupId).get(), false).message().contains("region file"), "backup with a damaged region file: FAILED");
		Path w4 = world(saves, "Plain", "x", true, false); Files.writeString(w4.resolve("level.dat"), "plain text"); String id4 = c.worlds.idFor("Plain");
		var b4 = c.backups.run(new BackupManager.Request(w4, "Plain", id4, BackupType.MANUAL, "1.21.4"));
		check(c.runTest(c.store.find(b4.backupId).get(), false).message().contains("cannot be read"), "backup whose level.dat is not NBT: FAILED");
		Files.delete(c.store.zipPath(good)); c.store.update(good.backupId, m -> m.localFilePresent = false);
		var sk = c.runTest(c.store.find(good.backupId).get(), false);
		var dl = c.runTest(c.store.find(good.backupId).get(), true);
		check(sk.outcome() == RestoreTester.Outcome.SKIPPED && dl.outcome() == RestoreTester.Outcome.OK, "cloud-only backup: automatic test skips (no download), a manual test downloads and passes");

		// ---- 7. automatic scheduling ----
		CloudVaultCore c2 = new CloudVaultCore(tmp.resolve("cv2"), sink); c2.config.restoreTestDays = 1;
		c2.config.global.session.joinEnabled = false; c2.config.global.session.leaveEnabled = false;
		String wid5 = c2.worlds.idFor("Upd World");
		c2.onWorldJoined(wDir, "Upd World", "Upd World", "1.21.4");
		var gb = c2.backups.run(new BackupManager.Request(wDir, "Upd World", wid5, BackupType.MANUAL, "1.21.4"));
		c2.maybeRestoreTest(); c2.queue.awaitIdle(10_000);
		check(c2.store.find(gb.backupId).get().testedAt > 0, "automatic restore test runs by itself when due");
		long when = c2.store.find(gb.backupId).get().testedAt;
		java.lang.reflect.Field lt = CloudVaultCore.class.getDeclaredField("lastTestPoll"); lt.setAccessible(true); lt.setLong(c2, 0);
		c2.maybeRestoreTest(); c2.queue.awaitIdle(5000);
		check(c2.store.find(gb.backupId).get().testedAt == when, "...and not again before the interval has passed");
		c2.setWorldEnabled(wid5, "Upd World", false); c2.store.update(gb.backupId, m -> m.testedAt = 0); lt.setLong(c2, 0);
		c2.maybeRestoreTest(); c2.queue.awaitIdle(3000);
		check(c2.store.find(gb.backupId).get().testedAt == 0, "no test for a world CloudVault is switched off for");
		c2.shutdown();

		// ---- 8. space warnings ----
		notes.clear(); cloud.quotaPct = 95;
		java.lang.reflect.Field qa = CloudManager.class.getDeclaredField("quotaAt"); qa.setAccessible(true); qa.setLong(c.cloud, 0); // the hourly limit has passed
		var gb2 = c.backups.run(new BackupManager.Request(wDir, "Upd World", wid, BackupType.MANUAL, "1.21.4")); c.queue.awaitIdle(10_000);
		check(c.cloud.quotaPercent() == 95 && notes.stream().anyMatch(n -> n.contains("95% full")) && c.spaceWarning() != null && c.spaceWarning().contains("95%"), "cloud 95% full: warned once, shown on the status screen");
		cloud.quotaPct = -1;
		check(c.backups.freeBytes() > 0 && BackupManager.LOW_DISK_BYTES == 2L * 1024 * 1024 * 1024, "free disk space can be read; the low-space limit is 2 GB");

		// ---- 9. export ----
		BackupMetadata ex = c.store.find(gb2.backupId).get(); Path out = tmp.resolve("exports/" + ex.fileName());
		c.cloud.exportZip(ex, out);
		try (ZipFile zf = new ZipFile(out.toFile())) { check(zf.getEntry("data.txt") != null && out.getFileName().toString().equals(ex.fileName()), "export: a normal ZIP named like the backup"); }

		c.shutdown();
		System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
