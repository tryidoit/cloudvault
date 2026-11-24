import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.*;
import com.cloudvault.config.*;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.HashUtil;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Engine-level robustness tests for Milestone 3 (no Minecraft needed). */
public class Milestone3Test {
	static int fails = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

	static class Fake implements CloudProvider {
		Map<String, byte[]> files = new LinkedHashMap<>(); Map<String, String> names = new HashMap<>(), folders = new HashMap<>(); int n = 0;
		public String id() { return "fake"; } public boolean isAuthenticated() { return true; }
		public void authenticate() {} public void signOut() {}
		public Status testConnection() { return new Status(true, "fake", "ok"); }
		public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException {
			byte[] b = Files.readAllBytes(f); String rid = "f" + (++n); files.put(rid, b); names.put(rid, name); folders.put(rid, folder);
			return new RemoteFile(rid, name, b.length, HashUtil.hashFile(f).md5(), props);
		}
		public void downloadBackup(String rid, Path t) throws IOException { Files.write(t, files.get(rid)); }
		public List<RemoteFile> listBackups(String w) { List<RemoteFile> l = new ArrayList<>(); files.forEach((k, v) -> l.add(new RemoteFile(k, names.getOrDefault(k, "backup-x.zip"), v.length, null, folders.get(k) == null ? Map.of() : Map.of("cv_folder", folders.get(k))))); return l; }
		public void deleteBackup(String rid) { files.remove(rid); }
	}

	static Path makeWorld(Path saves, String name, String data) throws IOException {
		Path w = saves.resolve(name); Files.createDirectories(w.resolve("region"));
		Files.writeString(w.resolve("level.dat"), "LEVEL"); Files.writeString(w.resolve("region/r.0.0.mca"), data); return w;
	}

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cvm3");
		Path world = makeWorld(tmp.resolve("saves"), "My World", "DATA-1");

		// ---- 1. CloudVault off by default for new worlds ----
		CloudVaultCore core = new CloudVaultCore(tmp.resolve("cv"), NotificationSink.NONE);
		Fake cloud = new Fake(); core.cloud.setProvider(cloud); core.config.retryDelaySeconds = 3600;
		String wid = core.worlds.idFor("My World");
		var req = new BackupManager.Request(world, "My World", wid, BackupType.MANUAL, "26.3");
		check(!core.isWorldDisabled(wid) && core.config.newWorldsEnabled && core.config.showWorldIndicator, "defaults: on for every world, indicator shown");
		BackupMetadata first = core.backups.run(req); Thread.sleep(400);
		check(first != null, "world with backups exists before changing the default");
		core.setNewWorldsEnabled(false);
		String fresh = core.worlds.idFor("Fresh World");
		Path freshDir = makeWorld(tmp.resolve("saves"), "Fresh World", "NEW");
		var freshReq = new BackupManager.Request(freshDir, "Fresh World", fresh, BackupType.MANUAL, "26.3");
		check(!core.isWorldDisabled(wid), "default off: a world that already has backups stays ON");
		check(core.isWorldDisabled(fresh) && core.backups.run(freshReq) == null && core.store.forWorld(fresh).isEmpty(), "default off: a new world is OFF and nothing is backed up");
		check(core.isWorldDisabled("brand-new-id") && !core.isWorldDisabled(null), "default off: unknown world ids are OFF; no world open is not an error");
		core.setWorldEnabled(fresh, "Fresh World", true);
		check(!core.isWorldDisabled(fresh) && core.backups.run(freshReq) != null, "default off: ticking the checkbox turns that world on");
		core.setWorldEnabled(wid, "My World", false);
		core.setNewWorldsEnabled(true);
		check(core.isWorldDisabled(wid) && !core.isWorldDisabled("brand-new-id"), "default back on: explicit OFF is kept, others follow the default");
		core.setWorldEnabled(wid, "My World", true);

		// ---- 2. config robustness ----
		Path cf = tmp.resolve("config.json");
		Files.writeString(cf, "{ this is not json");
		var c1 = CloudVaultConfig.load(cf);
		check(c1.enabled && Files.exists(tmp.resolve("config.json.corrupt-" + 0)) == false && tmp.toFile().list((d, n) -> n.startsWith("config.json.corrupt-")).length == 1, "malformed config: defaults used, broken file kept as a copy");
		Files.writeString(cf, "{\"global\":null,\"worlds\":null,\"retryDelaySeconds\":-5,\"compressionLevel\":99,\"localCacheKeep\":-1}");
		var c2 = CloudVaultConfig.load(cf);
		check(c2.global != null && c2.worlds != null && c2.retryDelaySeconds >= 10 && c2.compressionLevel == 9 && c2.localCacheKeep == 0, "null sections and out-of-range numbers are repaired");
		Files.writeString(cf, "{\"global\":{\"periodic\":{\"amount\":-3,\"unit\":null},\"gameplay\":{\"amount\":0},\"session\":{\"retention\":9999},\"manual\":null},\"worlds\":{\"w\":null,\"x\":{\"settings\":null}}}");
		var c3 = CloudVaultConfig.load(cf);
		check(c3.global.periodic.amount >= 1 && c3.global.periodic.unit != null && c3.global.session.retention <= BackupSettings.MAX_RETENTION && c3.global.manual != null && c3.worlds.get("x").settings != null && !c3.worlds.containsKey("w"), "invalid intervals, retention, null units and null worlds are repaired");
		Files.writeString(cf, "{\"worlds\":{\"old\":{\"disabled\":true},\"keep\":{\"enabled\":true,\"disabled\":true}}}");
		var c4 = CloudVaultConfig.load(cf);
		check(c4.isWorldDisabled("old") && !c4.isWorldDisabled("keep"), "old 'disabled' setting is converted; the new explicit choice wins");

		// ---- 3. corrupt metadata / stale temp from a crash ----
		core.shutdown();
		Path root = tmp.resolve("cv");
		Files.writeString(root.resolve("metadata/garbage.json"), "{{{ not json");
		Files.writeString(root.resolve("metadata/empty.json"), "");
		Path stale = root.resolve("temp/crashed-backup"); Files.createDirectories(stale); Files.writeString(stale.resolve("partial.zip"), "PK-half-written");
		Files.writeString(root.resolve("temp/x.upload.cvenc"), "half");
		CloudVaultCore core2 = new CloudVaultCore(root, NotificationSink.NONE);
		check(core2.store.all().size() >= 2 && Files.exists(root.resolve("metadata/garbage.json")), "corrupt metadata is skipped (and never deleted); good backups still load");
		check(!Files.exists(stale) && !Files.exists(root.resolve("temp/x.upload.cvenc")), "leftovers of a crashed backup/upload are cleaned at startup");
		check(core2.store.all().stream().allMatch(m -> m.backupId != null && m.worldId != null), "partial files never become backups");

		// ---- 4. crash during upload: pending work survives a restart ----
		BackupMetadata m = core2.store.all().get(0);
		m.uploadStatus = UploadStatus.UPLOADING; m.remoteId = null; core2.store.save(m);
		core2.shutdown();
		CloudVaultCore core3 = new CloudVaultCore(root, NotificationSink.NONE); Fake cloud2 = new Fake(); core3.setProvider(cloud2);
		core3.queue.awaitIdle(10_000); Thread.sleep(400);
		BackupMetadata after = core3.store.find(m.backupId).get();
		check(after.uploadStatus == UploadStatus.UPLOADED_VERIFIED && !cloud2.files.isEmpty(), "interrupted upload resumes after restart and is verified");

		// ---- 5. previous valid backup survives a failed replacement ----
		core3.config.global.session.retention = 0; // keep only the newest
		var join = new BackupManager.Request(world, "My World", wid, BackupType.SESSION_JOIN, "26.3");
		BackupMetadata good = core3.backups.run(join); core3.queue.awaitIdle(10_000);
		var broken = new BackupManager.Request(tmp.resolve("saves/missing-world"), "My World", wid, BackupType.SESSION_JOIN, "26.3");
		BackupMetadata bad = core3.backups.run(broken);
		long sessionCount = core3.store.forWorld(wid).stream().filter(x -> x.backupType == BackupType.SESSION_JOIN).count();
		check(good != null && bad == null && core3.store.find(good.backupId).isPresent() && Files.exists(core3.store.zipPath(good)) && sessionCount == 1, "failed replacement never deletes the previous valid backup");

		// ---- 6. many triggers at once on the same world ----
		Files.writeString(world.resolve("region/r.0.0.mca"), "DATA-2");
		List<Thread> ts = new ArrayList<>();
		for (BackupType t : new BackupType[]{BackupType.MANUAL, BackupType.PERIODIC, BackupType.SESSION_JOIN, BackupType.GAMEPLAY, BackupType.PERIODIC, BackupType.PERIODIC, BackupType.MANUAL}) {
			Thread th = new Thread(() -> core3.backups.submit(new BackupManager.Request(world, "My World", wid, t, "26.3"), null)); ts.add(th); th.start();
		}
		for (Thread th : ts) th.join();
		core3.queue.awaitIdle(30_000);
		int bad2 = 0;
		for (BackupMetadata x : core3.store.forWorld(wid)) {
			if (x.localFilePresent) { try { BackupVerifier.verifyZip(core3.store.zipPath(x)); BackupVerifier.verifyChecksum(core3.store.zipPath(x), x.sha256); } catch (IOException e) { bad2++; } }
		}
		long periodic = core3.store.forWorld(wid).stream().filter(x -> x.backupType == BackupType.PERIODIC).count();
		check(bad2 == 0 && periodic <= 1, "7 simultaneous triggers: every archive is valid and identical periodic requests are not repeated (" + periodic + " periodic)");
		check(core3.store.all().stream().noneMatch(x -> x.uploadStatus == UploadStatus.UPLOADING), "no backup is left stuck in UPLOADING");

		// ---- 7. zip extraction: hostile entry names ----
		Method ex = Class.forName("com.cloudvault.restore.RestoreManager").getDeclaredMethod("extractSafely", Path.class, Path.class);
		ex.setAccessible(true);
		String[] evil = {"../evil.txt", "a/../../evil.txt", "/abs/evil.txt", "\\\\server\\\\share\\\\x", "C:\\\\evil.txt", "..\\\\evil.txt", "a/b/../../../evil.txt", "ok/\u0000x"};
		int blocked = 0; List<String> notBlocked = new ArrayList<>();
		for (String name : evil) {
			Path z = tmp.resolve("evil.zip");
			try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) {
				zo.putNextEntry(new ZipEntry("level.dat")); zo.write("L".getBytes()); zo.closeEntry();
				zo.putNextEntry(new ZipEntry(name)); zo.write("X".getBytes()); zo.closeEntry();
			} catch (Exception e) { blocked++; continue; }
			Path dest = tmp.resolve("extract-" + blocked + "-" + Math.abs(name.hashCode()));
			try { ex.invoke(null, z, dest); notBlocked.add(name); } catch (Exception e) { blocked++; }
		}
		boolean escaped = Files.exists(tmp.resolve("evil.txt")) || Files.exists(tmp.getParent().resolve("evil.txt")) || Files.exists(Path.of("/abs/evil.txt"));
		check(blocked == evil.length && !escaped, "zip traversal: all " + evil.length + " hostile entry names refused, nothing written outside");

		// ---- 8. readable cloud file names ----
		boolean namesOk = true; String firstBad = "";
		String[] worldNames = {"My World", "W\u00f6rld_2 - final", "a_b_c", "x y z", "\u00dc\u00f1\u00ed\u00e7\u00f8d\u00e9 \u4e16\u754c", "New World (1)", "A".repeat(100), "2026_10_06_PDT_JOIN_x"};
		for (BackupType t : BackupType.values()) for (String wn : worldNames) {
			String id = "ecc204ec-60f2-4b86-954d-2dc2d5ad5c6b";
			String fn = CloudFileName.build(1791324634000L, t, wn, id);
			var pr = CloudFileName.parse(fn); var pe = CloudFileName.parse(fn + ".cvenc");
			String expectWorld = com.cloudvault.util.FileUtil.safeName(wn); if (expectWorld.length() > 40) expectWorld = expectWorld.substring(0, 40).trim();
			if (pr == null || pr.type() != t || !pr.worldName().equals(expectWorld) || !pr.id8().equals("ecc204ec") || pr.encrypted() || pe == null || !pe.encrypted() || pe.type() != t) { namesOk = false; firstBad = fn; }
		}
		check(namesOk, "file name round trip: every type x tricky world names (underscores, dashes, spaces, unicode, long)" + (namesOk ? "" : " bad: " + firstBad));
		var demo = CloudFileName.parse(CloudFileName.build(1791324634000L, BackupType.SESSION_JOIN, "My World", "ecc204ec-60f2-4b86-954d-2dc2d5ad5c6b"));
		check(demo.type() == BackupType.SESSION_JOIN && demo.when().matches("\\d{4}-\\d\\d-\\d\\d  \\d\\d:\\d\\d:\\d\\d .+") && demo.approxMillis() > 0, "parsed name shows a clean date, time and zone: " + demo.when());
		check(CloudFileName.parse("backup-2026-10-05_10-00-00_UTC-abcd1234.zip").type() == null && CloudFileName.parse("backup-ecc204ec-60f2-4b86-954d-2dc2d5ad5c6b.zip").id8().equals("ecc204ec"), "older file names are still understood");
		check(CloudFileName.parse("notes.txt") == null && CloudFileName.parse("backup-.zip") == null && CloudFileName.parse("backup-2026-10-06_15-10-34_PDT_HACK_x_ecc204ec.zip") == null && CloudFileName.parse(null) == null, "other files in the folder are ignored");

		// ---- 9. import menu: browse worlds in the cloud, then import only what was chosen ----
		Fake menu = new Fake();
		long day = 86_400_000L, t0 = 1791324634000L; int k = 0;
		for (Object[] row : new Object[][]{{"Alpha [11111111]", "Alpha", BackupType.SESSION_JOIN, 0}, {"Alpha [11111111]", "Alpha", BackupType.PERIODIC, 1}, {"Alpha [11111111]", "Alpha", BackupType.MANUAL, 2}, {"Beta [22222222]", "Beta", BackupType.SESSION_LEAVE, 3}}) {
			String id = String.format("%08d-0000-0000-0000-000000000000", k + 11111111); String fn = CloudFileName.build(t0 + (int) row[3] * day, (BackupType) row[2], (String) row[1], id);
			String rid = "m" + (++k); menu.files.put(rid, ("zip" + k).getBytes()); menu.names.put(rid, fn); menu.folders.put(rid, (String) row[0]);
		}
		menu.files.put("junk", new byte[3]); menu.names.put("junk", "readme.txt"); menu.folders.put("junk", "Alpha [11111111]");
		CloudVaultCore c5 = new CloudVaultCore(tmp.resolve("cv5"), NotificationSink.NONE); c5.setProvider(menu);
		var snap = c5.importer.fetch();
		var ws = c5.importer.worlds(snap);
		check(ws.size() == 2 && ws.get(0).worldName().equals("Beta") && ws.get(1).worldName().equals("Alpha") && ws.get(1).count() == 3 && ws.get(1).notImported() == 3 && ws.get(1).id8().equals("11111111"), "import menu lists the worlds found in the cloud (newest activity first), ignoring other files");
		var es = c5.importer.entries(snap, "Alpha [11111111]");
		check(es.size() == 3 && es.get(0).parsed().type() == BackupType.MANUAL && es.get(2).parsed().type() == BackupType.SESSION_JOIN && es.stream().noneMatch(e -> e.imported()), "a world's backups are listed newest first with type and time read from the file name");
		var one = c5.importer.importFiles(List.of(es.get(1).file()));
		var snap2 = c5.importer.fetch();
		check(one.imported() == 1 && c5.store.all().size() == 1 && c5.store.all().get(0).backupType == BackupType.PERIODIC && c5.store.all().get(0).worldName.equals("Alpha"), "importing one chosen backup imports only that one, with the right type and world name");
		check(c5.importer.entries(snap2, "Alpha [11111111]").stream().filter(e -> e.imported()).count() == 1 && c5.importer.worlds(snap2).stream().filter(w -> w.worldName().equals("Alpha")).findFirst().get().notImported() == 2, "the menu then shows it as already imported");
		var rest = c5.importer.importFiles(c5.importer.entries(snap2, "Alpha [11111111]").stream().filter(e -> !e.imported()).map(e -> e.file()).toList());
		check(rest.imported() == 2 && c5.store.all().size() == 3 && c5.importer.importAll().imported() == 1, "import all in this world adds the rest; import-everything then adds the other world");
		c5.shutdown();

		// ---- 10. friendly errors, no secrets in messages ----
		check(com.cloudvault.util.ErrorText.friendly(new IOException("No space left on device")).startsWith("The disk is full") && com.cloudvault.util.ErrorText.friendly(new java.net.UnknownHostException("www.googleapis.com")).startsWith("No internet")
			&& com.cloudvault.util.ErrorText.friendly(new java.nio.file.AccessDeniedException("/x")).startsWith("Permission denied") && com.cloudvault.util.ErrorText.friendly(new java.util.zip.ZipException("invalid entry")).contains("damaged")
			&& com.cloudvault.util.ErrorText.friendly(new CloudException(CloudException.Kind.AUTH, "Google Drive sign-in expired")).equals("Google Drive sign-in expired"), "errors become short messages that say what to do");
		String red = com.cloudvault.util.ErrorText.redact("401 Bearer ya29.A0AfH6SMB-secretvalue_123 failed; access_token=abc123&code=4/0Ax; client_secret=GOCSPX-abc_DEF-123 and GOCSPX-zzz");
		check(!red.contains("ya29") && !red.contains("abc123") && !red.contains("4/0Ax") && !red.contains("GOCSPX") && red.contains("[hidden]"), "tokens, codes and client secrets are removed from messages: " + red);

		// ---- 11. notifications are not spammy ----
		List<String> got = new ArrayList<>();
		var dd = new com.cloudvault.notification.DedupeSink((lv, ti, ms2) -> got.add(lv + ms2), 60_000);
		for (int i = 0; i < 5; i++) dd.notify(NotificationSink.Level.WARNING, "CloudVault", "No internet");
		dd.notify(NotificationSink.Level.ERROR, "CloudVault", "Upload failed"); dd.notify(NotificationSink.Level.SUCCESS, "CloudVault", "Backup saved"); dd.notify(NotificationSink.Level.SUCCESS, "CloudVault", "Backup saved");
		check(got.size() == 4, "the same persistent warning shows once; different problems and successes still show (" + got + ")");

		// ---- 12. activity status ----
		CloudVaultCore c6 = new CloudVaultCore(tmp.resolve("cv6"), NotificationSink.NONE);
		c6.config.newWorldsEnabled = true; String w6 = c6.worlds.idFor("My World");
		c6.backups.run(new BackupManager.Request(world, "My World", w6, BackupType.MANUAL, "26.3"));
		check(c6.activity.phase() == Activity.Phase.IDLE && c6.activity.lastSuccess().contains("My World") && c6.activity.lastSuccessAt() > 0, "activity: idle again after a backup, last success remembered");
		c6.backups.run(new BackupManager.Request(tmp.resolve("saves/nope"), "Nope", w6, BackupType.MANUAL, "26.3"));
		check(c6.activity.phase() == Activity.Phase.IDLE && c6.activity.lastProblem().contains("Nope"), "activity: a failure is remembered for the status screen");

		// ---- 13. metadata index: fast, and always consistent ----
		for (int i = 0; i < 3000; i++) { BackupMetadata bm = new BackupMetadata(); bm.worldId = "idx-world-" + (i % 5); bm.worldName = "W"; bm.backupType = BackupType.PERIODIC; bm.timestamp = i; bm.sha256 = "x"; c6.store.save(bm); }
		long t1 = System.nanoTime(); int found = 0;
		for (BackupMetadata bm : c6.store.forWorld("idx-world-1")) if (c6.store.find(bm.backupId).isPresent()) found++;
		long ms = (System.nanoTime() - t1) / 1_000_000;
		check(found == 600 && ms < 1500, "3000 backups: 600 lookups in " + ms + " ms (no re-reading of files)");
		BackupMetadata first1 = c6.store.forWorld("idx-world-1").get(0); BackupMetadata edit = first1.copy(); edit.lastError = "changed";
		check(c6.store.find(first1.backupId).get().lastError == null, "a changed copy does not alter stored data until it is saved");
		c6.store.save(edit); check("changed".equals(c6.store.find(first1.backupId).get().lastError), "saving updates what everyone sees");
		CloudVaultCore c7 = new CloudVaultCore(tmp.resolve("cv6"), NotificationSink.NONE);
		check("changed".equals(c7.store.find(first1.backupId).get().lastError) && c7.store.all().size() == c6.store.all().size(), "a restart sees exactly the saved data");
		c6.store.deleteLocal(edit); check(c6.store.find(edit.backupId).isEmpty() && !Files.exists(root.resolve("..").resolve("cv6/metadata/" + edit.backupId + ".json")), "delete removes it from the index and from disk");
		c7.shutdown();

		// ---- 14. queue: waiting for idle is exact; secrets are owner-only ----
		long q0 = System.currentTimeMillis();
		c6.queue.submit(5, null, () -> { try { Thread.sleep(400); } catch (InterruptedException ignored) {} });
		c6.queue.awaitIdle(5000);
		check(System.currentTimeMillis() - q0 >= 380, "awaitIdle really waits for a running job");
		Path secret = tmp.resolve("secret.txt"); com.cloudvault.util.FileUtil.writeSecret(secret, "s3cr3t");
		try { check(Files.getPosixFilePermissions(secret).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) && Files.readString(secret).equals("s3cr3t"), "key/token files are readable by their owner only"); }
		catch (UnsupportedOperationException e) { check(true, "key/token files: owner-only permissions not applicable on this file system"); }
		c6.shutdown();

		// ---- 15. insecure storage address is refused ----
		var plain = new com.cloudvault.cloud.s3.S3Provider(() -> new com.cloudvault.cloud.s3.S3Provider.Cfg("http://storage.example.com", "auto", "b", "AK", "SK", true));
		var st = plain.testConnection();
		check(!st.connected() && st.message().contains("https"), "S3 over plain http to a remote server is refused: " + st.message());

		// ---- 16. two threads saving the same file never mix data; no temp files left ----
		Path shared = tmp.resolve("shared.json"); List<Thread> writers = new ArrayList<>(); List<Throwable> werr = Collections.synchronizedList(new ArrayList<>());
		for (int w = 0; w < 8; w++) { final int id = w; Thread th = new Thread(() -> { try { for (int i = 0; i < 150; i++) com.cloudvault.util.FileUtil.writeAtomic(shared, "{\"writer\":" + id + ",\"pad\":\"" + "x".repeat(2000) + "\"}"); } catch (Throwable e) { werr.add(e); } }); writers.add(th); th.start(); }
		for (Thread th : writers) th.join();
		String finalText = Files.readString(shared);
		boolean whole = finalText.startsWith("{\"writer\":") && finalText.endsWith("\"}") && finalText.length() > 2000;
		try (var ls = Files.list(tmp)) { check(werr.isEmpty() && whole && ls.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "8 threads x 150 saves of one file: always a complete file, no .tmp left over"); }

		// ---- 17. tampered metadata and world list can never point outside their folders ----
		Path cvT = tmp.resolve("cvT"); Files.createDirectories(cvT.resolve("metadata"));
		Path victim = tmp.resolve("victim.txt"); Files.writeString(victim, "precious");
		String evilJson = "{\"backupId\":\"11111111-1111-1111-1111-111111111111\",\"worldId\":\"../../x\",\"worldName\":\"W\",\"backupType\":\"MANUAL\",\"zipFileName\":\"../../victim.txt\",\"sha256\":\"x\"}";
		String evil2 = evilJson.replace("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222").replace("../../x", "okworld");
		Files.writeString(cvT.resolve("metadata/a.json"), evilJson); Files.writeString(cvT.resolve("metadata/b.json"), evil2);
		Files.writeString(cvT.resolve("worlds.json"), "{\"../../etc\":\"abc\",\"ok world\":\"12345678-aaaa\",\"a/b\":\"zzz\",\"good\":\"../bad\"}");
		CloudVaultCore cT = new CloudVaultCore(cvT, NotificationSink.NONE);
		check(cT.store.all().isEmpty(), "metadata with path tricks in worldId or file name is ignored");
		BackupMetadata bad3 = new BackupMetadata(); bad3.worldId = "../w"; bad3.worldName = "W"; bad3.backupType = BackupType.MANUAL;
		try { cT.store.save(bad3); check(false, "invalid record refused"); } catch (IOException e) { check(true, "an invalid backup record is refused when saving"); }
		check(Files.readString(victim).equals("precious") && cT.worlds.folderFor("abc") == null && cT.worlds.idIfKnown("ok world") != null && cT.worlds.idIfKnown("a/b") == null && cT.worlds.idIfKnown("good") == null, "world list: only plain folder names and ids are accepted; unrelated files untouched");
		cT.shutdown();

		// ---- 18. fingerprint: sees every change, ignores order and the lock file, and is fast ----
		Path fp = tmp.resolve("fpworld"); Files.createDirectories(fp.resolve("region"));
		for (int i = 0; i < 20000; i++) Files.writeString(fp.resolve("region/r." + i + ".mca"), "d" + i);
		Files.writeString(fp.resolve("session.lock"), "lock");
		long f0 = System.nanoTime(); String fa = com.cloudvault.util.FileUtil.fingerprint(fp); long fms = (System.nanoTime() - f0) / 1_000_000;
		Files.writeString(fp.resolve("session.lock"), "lock-changed");
		boolean same = fa.equals(com.cloudvault.util.FileUtil.fingerprint(fp));
		Files.writeString(fp.resolve("region/r.5.mca"), "changed!"); String fb = com.cloudvault.util.FileUtil.fingerprint(fp);
		Files.delete(fp.resolve("region/r.6.mca")); String fc = com.cloudvault.util.FileUtil.fingerprint(fp);
		Files.writeString(fp.resolve("new.dat"), "n"); String fd = com.cloudvault.util.FileUtil.fingerprint(fp);
		check(same && !fa.equals(fb) && !fb.equals(fc) && !fc.equals(fd) && fms < 5000, "fingerprint of 20000 files in " + fms + " ms: detects edits, deletions and new files, ignores session.lock");

		// ---- 19. more hostile ZIP entry names (Windows device names, trailing dots/spaces) ----
		int blocked2 = 0; String[] evil3 = {"region/CON", "aux.txt", "COM1/x", "a /b", "dir./f", "LPT9.dat", "NUL", "x/prn.mca"};
		for (String name : evil3) {
			Path z = tmp.resolve("evil2.zip");
			try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(z))) { zo.putNextEntry(new ZipEntry("level.dat")); zo.write("L".getBytes()); zo.closeEntry(); zo.putNextEntry(new ZipEntry(name)); zo.write("X".getBytes()); zo.closeEntry(); }
			try { ex.invoke(null, z, tmp.resolve("ext2-" + Math.abs(name.hashCode()))); } catch (Exception e) { blocked2++; }
		}
		Path okz = tmp.resolve("ok.zip");
		try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(okz))) { for (String n : new String[]{"level.dat", "region/r.0.0.mca", "DIM-1/region/r.0.0.mca", "playerdata/ab12.dat", "datapacks/pack.zip", "data/raids.dat", "icon.png"}) { zo.putNextEntry(new ZipEntry(n)); zo.write(1); zo.closeEntry(); } }
		boolean okExtract = true; try { ex.invoke(null, okz, tmp.resolve("ext-ok")); } catch (Exception e) { okExtract = false; }
		check(blocked2 == evil3.length && okExtract, "device names and trailing dots/spaces refused; every normal Minecraft world file name still extracts");

		// ---- 20. OAuth sign-in: a stray request cannot end or hijack the real sign-in ----
		var auth = new com.cloudvault.cloud.google.GoogleAuth(tmp.resolve("authT"), () -> "client-id", () -> "");
		java.util.concurrent.atomic.AtomicReference<String> openedUrl = new java.util.concurrent.atomic.AtomicReference<>();
		auth.setUrlOpener(openedUrl::set);
		java.util.concurrent.atomic.AtomicReference<Throwable> signErr = new java.util.concurrent.atomic.AtomicReference<>();
		Thread si = new Thread(() -> { try { auth.signIn(); } catch (Throwable e) { signErr.set(e); } }); si.start();
		for (int i = 0; i < 50 && openedUrl.get() == null; i++) Thread.sleep(100);
		String authUrl = openedUrl.get();
		check(authUrl != null && authUrl.startsWith("https://accounts.google.com/") && authUrl.contains("code_challenge_method=S256") && !authUrl.contains("client_secret"), "sign-in uses Google's https page with PKCE and no secret in the link");
		java.util.regex.Matcher mm = java.util.regex.Pattern.compile("redirect_uri=([^&]+)").matcher(authUrl); mm.find();
		String redirect = java.net.URLDecoder.decode(mm.group(1), "UTF-8"); String oauthState = authUrl.replaceAll(".*[?&]state=([^&]+).*", "$1");
		var hc = java.net.http.HttpClient.newHttpClient();
		int strayStatus = hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(redirect + "?state=wrong&code=stolen")).build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
		Thread.sleep(300);
		check(redirect.startsWith("http://127.0.0.1:") && strayStatus == 400 && si.isAlive() && signErr.get() == null, "sign-in callback listens on loopback only; a request with the wrong state is rejected and the sign-in keeps waiting");
		hc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(redirect + "?state=" + oauthState + "&error=access_denied")).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
		si.join(5000);
		check(!si.isAlive() && signErr.get() instanceof CloudException ce && ce.kind == CloudException.Kind.AUTH, "cancelling in the browser ends the sign-in with a clear message");

		core3.shutdown();
		System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
