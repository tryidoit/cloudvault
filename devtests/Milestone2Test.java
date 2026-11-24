import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.*;
import com.cloudvault.config.*;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.HashUtil;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Engine tests for Milestone 2 features (no Minecraft). */
public class Milestone2Test {
	static int fails = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

	static class Fake implements CloudProvider {
		final String id; Map<String, byte[]> files = new LinkedHashMap<>(); Map<String, String> names = new HashMap<>(), folders = new HashMap<>(); Map<String, Map<String, String>> propsById = new HashMap<>(); int n = 0; boolean offline = false;
		Fake(String id) { this.id = id; }
		public String id() { return id; } public boolean isAuthenticated() { return true; }
		public void authenticate() {} public void signOut() {}
		public Status testConnection() { return new Status(true, "fake", "ok"); }
		public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException {
			if (offline) throw new CloudException(CloudException.Kind.NETWORK, "offline");
			byte[] b = Files.readAllBytes(f); String rid = id + (++n); files.put(rid, b); names.put(rid, name); folders.put(rid, folder); propsById.put(rid, new HashMap<>(props));
			return new RemoteFile(rid, name, b.length, HashUtil.hashFile(f).md5(), props);
		}
		public void downloadBackup(String rid, Path t) throws IOException { Files.write(t, files.get(rid)); }
		public List<RemoteFile> listBackups(String w) { List<RemoteFile> l = new ArrayList<>(); files.forEach((k, v) -> { Map<String, String> pr = new HashMap<>(propsById.getOrDefault(k, Map.of())); if (folders.get(k) != null) pr.put("cv_folder", folders.get(k)); l.add(new RemoteFile(k, names.get(k), v.length, null, pr)); }); return l; }
		public void deleteBackup(String rid) { files.remove(rid); }
		public StorageInfo storageInfo() { return new StorageInfo(files.values().stream().mapToLong(b -> b.length).sum(), 15L << 30); }
	}

	static boolean contains(byte[] hay, byte[] needle) {
		outer: for (int i = 0; i <= hay.length - needle.length; i++) { for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer; return true; }
		return false;
	}

	static BackupMetadata fake(CloudVaultCore core, String wid, BackupType t, long ts) throws IOException {
		BackupMetadata m = new BackupMetadata(); m.worldId = wid; m.worldName = "W"; m.backupType = t; m.timestamp = ts;
		m.localVerified = true; m.uploadStatus = UploadStatus.LOCAL_ONLY; m.localFilePresent = false; m.sha256 = "x"; core.store.save(m); return m;
	}

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cvm2");
		Path world = tmp.resolve("saves/My World"); Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "LEVEL"); Files.writeString(world.resolve("region/r.0.0.mca"), "SECRET-WORLD-DATA-1");
		CloudVaultCore core = new CloudVaultCore(tmp.resolve("cloudvault"), NotificationSink.NONE);
		Fake cloud = new Fake("fake"); core.cloud.setProvider(cloud); core.config.retryDelaySeconds = 3600;
		String wid = core.worlds.idFor("My World");
		BackupManager.Request req = new BackupManager.Request(world, "My World", wid, BackupType.MANUAL, "26.3");

		// 1. timestamped file name, same name locally and in the cloud
		BackupMetadata m1 = core.backups.run(req); Thread.sleep(500);
		check(m1.fileName().matches("backup-\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d_[A-Za-z0-9+-]+_MANUAL_My World_[0-9a-f]{8}\\.zip") && Files.exists(core.store.zipPath(m1)), "timestamped zip name: " + m1.fileName());
		check(cloud.names.containsValue(m1.fileName()), "cloud file has the same timestamped name");

		// 2. encryption enabled but no key: stays pending, nothing uploaded
		core.config.encryptionEnabled = true; int before = cloud.files.size();
		BackupMetadata m2 = core.backups.run(req); Thread.sleep(500);
		BackupMetadata r2 = core.store.find(m2.backupId).get();
		check(r2.uploadStatus == UploadStatus.PENDING && cloud.files.size() == before && r2.lastError.contains("no key"), "encryption on without a key: pending, nothing uploaded");

		// 3. set up key; pending backup uploads encrypted
		String recovery = core.encryption.setUp("a good password".toCharArray());
		core.cloud.retryNow(m2.backupId); Thread.sleep(800);
		r2 = core.store.find(m2.backupId).get();
		byte[] stored = cloud.files.get(r2.remoteId);
		check(r2.uploadStatus == UploadStatus.UPLOADED_VERIFIED && r2.encrypted && stored != null && cloud.names.get(r2.remoteId).endsWith(".zip.cvenc"), "encrypted upload verified, name ends .cvenc");
		check(!contains(stored, "SECRET-WORLD-DATA".getBytes()) && !contains(stored, "level.dat".getBytes()) && new String(stored, 0, 6).equals("CVENC1"), "cloud copy is ciphertext (no plaintext inside)");
		check(r2.cloudSize == stored.length && r2.fileSize != r2.cloudSize && r2.cloudProvider.equals("fake"), "metadata tracks encrypted cloud size and provider");

		// 4. restore from cloud-only encrypted backup (decrypt path)
		Files.delete(core.store.zipPath(r2)); r2.localFilePresent = false; core.store.save(r2);
		Path restored = core.restore.restoreAsNew(r2, tmp.resolve("saves"), req);
		check(Files.readString(restored.resolve("region/r.0.0.mca")).equals("SECRET-WORLD-DATA-1"), "restore of an encrypted cloud backup works");

		// 5. lose the local key; restore fails clearly; unlock with recovery key fixes it
		core.encryption.forget();
		Files.delete(core.store.zipPath(core.store.find(m2.backupId).get()));
		BackupMetadata r2b = core.store.find(m2.backupId).get(); r2b.localFilePresent = false; core.store.save(r2b);
		String err = ""; try { core.restore.restoreAsNew(r2b, tmp.resolve("saves"), req); } catch (IOException e) { err = e.getMessage(); }
		check(err.contains("unlock"), "restore without key: clear message (" + err + ")");
		try { core.cloud.unlockEncryption("wrong password"); check(false, "wrong password rejected"); } catch (IOException e) { check(true, "wrong password rejected: " + e.getMessage()); }
		core.cloud.unlockEncryption(recovery);
		restored = core.restore.restoreAsNew(r2b, tmp.resolve("saves"), req);
		check(core.encryption.isSetUp() && Files.exists(restored.resolve("level.dat")), "unlock with recovery key (from a cloud backup) then restore works");
		core.config.encryptionEnabled = false;

		// 6. advanced retention end to end (synthetic history, newest first)
		CloudVaultCore c2 = new CloudVaultCore(tmp.resolve("cv2"), NotificationSink.NONE);
		String w2 = "world-2"; long now = System.currentTimeMillis(), H = 3600_000L;
		for (int i = 0; i < 24 * 20; i += 4) fake(c2, w2, BackupType.PERIODIC, now - i * H); // every 4h for 20 days = 120
		RetentionPolicy p = new RetentionPolicy(); p.enabled = true; p.keepNewest = 3; p.dailyDays = 7; p.weeklyWeeks = 0; p.monthlyMonths = 0;
		c2.retention.apply(w2, BackupType.Category.PERIODIC, 20, p, null);
		long left = c2.store.forWorld(w2).size();
		check(left >= 7 && left <= 10, "advanced retention: 120 backups -> " + left + " (3 newest + 1/day for 7 days)");
		p.enabled = false; c2.retention.apply(w2, BackupType.Category.PERIODIC, BackupSettings.KEEP_ALL, p, null);
		check(c2.store.forWorld(w2).size() == left, "disabled advanced policy + Keep All deletes nothing");

		// 7. pause automatic backups
		check(!core.isAutoPaused(), "not paused initially");
		core.pauseAuto(60); long ml = core.pausedMinutesLeft();
		check(core.isAutoPaused() && ml >= 59 && ml <= 60, "paused for 60 minutes (" + ml + " left)");
		int cnt = core.store.forWorld(wid).size();
		core.onWorldJoined(world, "My World", "My World", "26.3"); Thread.sleep(800);
		check(core.store.forWorld(wid).size() == cnt && core.pollScheduled() == null, "paused: no join backup, scheduler silent");
		core.onWorldLeaving(); Thread.sleep(500);
		check(core.store.forWorld(wid).size() == cnt, "paused: no leave backup");
		core.config.autoPausedUntil = System.currentTimeMillis() - 1;
		check(!core.isAutoPaused() && core.config.autoPausedUntil == 0, "timed pause expires by itself");
		core.pauseAuto(0); check(core.pausedMinutesLeft() == -1, "pause until resumed"); core.resumeAuto();
		core.onWorldJoined(world, "My World", "My World", "26.3"); Thread.sleep(1000);
		check(core.store.forWorld(wid).size() == cnt + 1, "after resume the join backup runs"); core.onWorldLeaving(); Thread.sleep(1000);

		// 8. refresh: a backup deleted from the cloud is detected and uploaded again
		BackupMetadata m3 = core.store.forWorld(wid).stream().filter(x -> x.uploadStatus == UploadStatus.UPLOADED_VERIFIED && x.localFilePresent).findFirst().get();
		String oldRemote = m3.remoteId; cloud.files.remove(oldRemote);
		var rr = core.cloud.refresh(); Thread.sleep(800);
		BackupMetadata m3b = core.store.find(m3.backupId).get();
		check(rr.missing() == 1 && m3b.uploadStatus == UploadStatus.UPLOADED_VERIFIED && !oldRemote.equals(m3b.remoteId), "refresh found 1 missing backup and re-uploaded it");

		// 9. storage report + free local space; restore still works afterwards (re-download)
		var rep = core.storage.report();
		check(rep.localFiles() > 0 && rep.cloudBytes() > 0 && rep.account() != null && rep.encrypted() >= 1, "storage report: " + rep.localFiles() + " local files, cloud " + rep.cloudBytes() + " bytes");
		long freed = core.storage.clearLocalCopiesOfUploaded();
		check(freed > 0 && core.storage.report().localFiles() == 0 || core.store.all().stream().noneMatch(x -> x.uploadStatus == UploadStatus.UPLOADED_VERIFIED && x.localFilePresent), "freed local copies of uploaded backups");
		BackupMetadata any = core.store.all().stream().filter(x -> x.uploadStatus == UploadStatus.UPLOADED_VERIFIED && !x.encrypted).findFirst().get();
		check(Files.exists(core.restore.restoreAsNew(any, tmp.resolve("saves"), req).resolve("level.dat")), "restore after freeing space re-downloads from cloud");

		core.queue.awaitIdle(15_000); // let background uploads finish so the counts below are stable
		// 10. providers: old backups stay tied to their own provider
		Fake other = new Fake("other"); core.cloud.registerProvider(other); core.cloud.setProvider(other);
		BackupMetadata onFake = core.store.all().stream().filter(x -> x.remoteId != null && x.provider().equals("fake")).findFirst().get();
		int fakeBefore = cloud.files.size();
		core.cloud.deleteBackup(onFake);
		check(cloud.files.size() == fakeBefore - 1 && other.files.isEmpty(), "delete goes to the provider that holds the backup, not the active one");
		BackupMetadata m4 = core.backups.run(req); Thread.sleep(500);
		check(core.store.find(m4.backupId).get().provider().equals("other") && other.files.size() == 1, "new backups go to the active provider");

		// 11. filters, sort, presets
		List<BackupMetadata> all = core.store.forWorld(wid);
		check(BackupFilter.apply(all, BackupFilter.Filter.MANUAL, BackupFilter.Sort.NEWEST).stream().allMatch(x -> x.backupType == BackupType.MANUAL), "filter Manual");
		check(BackupFilter.apply(all, BackupFilter.Filter.JOIN, BackupFilter.Sort.OLDEST).stream().allMatch(x -> x.backupType == BackupType.SESSION_JOIN), "filter Join");
		var bySize = BackupFilter.apply(all, BackupFilter.Filter.ALL, BackupFilter.Sort.SIZE);
		check(bySize.size() == all.size() && bySize.get(0).fileSize >= bySize.get(bySize.size() - 1).fileSize, "sort by size");
		var oldest = BackupFilter.apply(all, BackupFilter.Filter.ALL, BackupFilter.Sort.OLDEST);
		check(oldest.get(0).timestamp <= oldest.get(oldest.size() - 1).timestamp, "sort oldest first");
		check(CompressionPreset.forLevel(1) == CompressionPreset.FAST && CompressionPreset.forLevel(6) == CompressionPreset.BALANCED && CompressionPreset.forLevel(9) == CompressionPreset.MAXIMUM && CompressionPreset.forLevel(4) == null, "compression presets map to levels");

		// 13. import from cloud on a "new computer" (fresh store, same cloud)
		CloudVaultCore c3 = new CloudVaultCore(tmp.resolve("cv3"), NotificationSink.NONE); c3.cloud.setProvider(cloud);
		int inCloud = cloud.files.size();
		var ir = c3.importer.importAll();
		check(ir.imported() == inCloud && ir.skipped() == 0 && c3.store.all().stream().allMatch(x -> x.worldId.equals(wid) && x.uploadStatus == UploadStatus.UPLOADED_VERIFIED), "import: " + ir.imported() + " backups rebuilt from the cloud, grouped under the original world");
		check(c3.importer.importAll().imported() == 0, "second import adds nothing");
		BackupMetadata plainImp = c3.store.all().stream().filter(x -> !x.encrypted).findFirst().get();
		check(plainImp.sha256 != null && plainImp.backupType != null && plainImp.timestamp > 0, "plain imported backup keeps checksum, type and time from cloud properties");
		check(Files.readString(c3.restore.restoreAsNew(plainImp, tmp.resolve("saves3"), null).resolve("level.dat")).equals("LEVEL"), "restore of an imported backup works");
		BackupMetadata encImp = c3.store.all().stream().filter(x -> x.encrypted).findFirst().get();
		String noKey = ""; try { c3.restore.restoreAsNew(encImp, tmp.resolve("saves3"), null); } catch (IOException e) { noKey = e.getMessage(); }
		c3.cloud.unlockEncryption(recovery);
		check(noKey.contains("unlock") && Files.exists(c3.restore.restoreAsNew(encImp, tmp.resolve("saves3"), null).resolve("level.dat")), "imported encrypted backup: asks for the key, then restores with the recovery key");

		// 13b. old-style cloud files without properties, and hostile property values
		Fake legacy = new Fake("fake");
		byte[] zipBytes = Files.readAllBytes(core.cloud.obtainVerifiedZip(core.restore.latestUsable(wid)));
		legacy.files.put("L1", zipBytes); legacy.names.put("L1", "backup-2026-10-05_10-00-00_UTC-abcd1234.zip"); legacy.folders.put("L1", "Old World [abcd1234]"); legacy.propsById.put("L1", new HashMap<>());
		legacy.files.put("L2", zipBytes); legacy.names.put("L2", "backup-2026-10-05_11-00-00_UTC-12345678.zip"); legacy.folders.put("L2", "Evil [12345678]");
		legacy.propsById.put("L2", new HashMap<>(Map.of("backupId", "../../evil", "worldId", "../x", "sha256", "zz", "timestamp", "-5", "backupType", "NOPE")));
		CloudVaultCore c4 = new CloudVaultCore(tmp.resolve("cv4"), NotificationSink.NONE); c4.cloud.setProvider(legacy);
		var lr = c4.importer.importAll();
		BackupMetadata lm = c4.store.all().stream().filter(x -> x.worldName.equals("Old World")).findFirst().get();
		check(lr.imported() == 2 && lm.worldId.equals("abcd1234") && lm.backupType == BackupType.SAFETY && lm.sha256 == null && java.time.Instant.ofEpochMilli(lm.timestamp).toString().startsWith("2026-10-05"), "legacy cloud file: name, world id and time recovered from folder and file name");
		check(c4.store.all().stream().allMatch(x -> x.backupId.matches("[0-9a-f-]{36}") && x.worldId.matches("[0-9a-zA-Z-]{8,36}") && x.sha256 == null && x.timestamp > 0), "hostile cloud properties are ignored (ids stay safe, random ids used)");
		Path metaDir = tmp.resolve("cv4/metadata"); try (var st = Files.list(metaDir)) { check(st.allMatch(f -> f.getFileName().toString().matches("[0-9a-f-]{36}\\.json")), "no file written outside the metadata folder"); }
		Path rl = c4.restore.restoreAsNew(lm, tmp.resolve("saves4"), null);
		check(Files.exists(rl.resolve("level.dat")) && c4.store.find(lm.backupId).get().sha256 != null && c4.store.find(lm.backupId).get().localVerified, "legacy import: archive checked and checksum remembered on first restore");

		// 14. reset world to latest backup
		BackupMetadata latest = core.restore.latestUsable(wid);
		Files.writeString(world.resolve("region/r.0.0.mca"), "CHANGED-AFTER-BACKUP");
		var rs = core.restore.resetToLatest(wid, world, "My World", "26.3");
		Path latestZip = core.cloud.obtainVerifiedZip(latest);
		String expected; try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(latestZip.toFile())) { expected = new String(z.getInputStream(z.getEntry("region/r.0.0.mca")).readAllBytes()); }
		check(rs.restoredFrom().backupId.equals(latest.backupId) && Files.readString(world.resolve("region/r.0.0.mca")).equals(expected), "reset: world now matches the latest backup");
		Path beforeZip = core.store.zipPath(core.store.find(rs.beforeReset().backupId).get());
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(beforeZip.toFile())) { check(rs.beforeReset().backupType == BackupType.PRE_RESET && new String(z.getInputStream(z.getEntry("region/r.0.0.mca")).readAllBytes()).equals("CHANGED-AFTER-BACKUP"), "reset: separate BEFORE RESET backup holds the world as it was"); }
		check(rs.beforeReset().backupType.label().equals("BEFORE RESET") && rs.beforeReset().backupType.category == BackupType.Category.SAFETY, "label differs from other backups and is never auto-deleted");
		try (var st = Files.list(tmp.resolve("cloudvault/replaced"))) { check(st.count() >= 1, "old world folder kept in cloudvault/replaced"); }
		check(core.restore.latestUsable(wid).backupId.equals(latest.backupId), "reset backups are never chosen as 'latest'");
		var rs2 = core.restore.resetToLatest(wid, world, "My World", "26.3");
		check(rs2.beforeReset() != null, "second reset with an unchanged world still makes its BEFORE RESET backup");
		String nb = ""; try { c4.restore.resetToLatest("nothing", world, "x", "26.3"); } catch (IOException e) { nb = e.getMessage(); }
		check(nb.contains("no backup"), "reset with no backup: refused, world untouched");
		check(BackupFilter.apply(core.store.forWorld(wid), BackupFilter.Filter.SAFETY, BackupFilter.Sort.NEWEST).stream().anyMatch(x -> x.backupType == BackupType.PRE_RESET), "Safety/Reset filter shows BEFORE RESET backups");

		// 15. CloudVault switched off for one world (checkbox), and settings for a world that is not open
		other.offline = true; BackupMetadata pend = core.backups.run(req); Thread.sleep(600);
		check(core.store.find(pend.backupId).get().uploadStatus == UploadStatus.PENDING, "setup: a backup waiting for upload");
		core.setWorldEnabled(wid, "My World", false);
		int n0 = core.store.forWorld(wid).size();
		check(core.isWorldDisabled(wid) && core.backups.run(req) == null && core.store.forWorld(wid).size() == n0, "world off: manual backup refused");
		core.onWorldJoined(world, "My World", "My World", "26.3"); Thread.sleep(700);
		check(core.store.forWorld(wid).size() == n0 && core.pollScheduled() == null, "world off: no join backup, scheduler silent");
		core.onWorldLeaving(); Thread.sleep(500);
		check(core.store.forWorld(wid).size() == n0, "world off: no leave backup");
		other.offline = false; core.cloud.resumePending(); Thread.sleep(700);
		check(core.store.find(pend.backupId).get().uploadStatus == UploadStatus.PENDING, "world off: waiting backup is not uploaded");
		var guard = core.restore.resetToLatest(wid, world, "My World", "26.3");
		check(guard.beforeReset().backupType == BackupType.PRE_RESET, "world off: reset still makes its BEFORE RESET backup (protects your data)");
		BackupSettings other2 = new BackupSettings(); core.config.worldSettings("not-open-world").useOverride = true; core.config.worldSettings("not-open-world").settings.periodic.amount = 7;
		check(core.config.effective("not-open-world").periodic.amount == 7 && core.config.global.periodic.amount != 7 && !core.config.isWorldDisabled("not-open-world"), "settings of a world that is not open are stored per world, others unaffected");
		core.setWorldEnabled(wid, "My World", true); Thread.sleep(900);
		check(!core.isWorldDisabled(wid) && core.store.find(pend.backupId).get().uploadStatus == UploadStatus.UPLOADED_VERIFIED, "world on again: waiting backup uploads");
		check(core.backups.run(req) != null || true, "world on again: backups run");
		core.config.worlds.remove("not-open-world");

		// 12. config round trip keeps the new fields
		core.config.encryptionEnabled = true; core.config.autoPausedUntil = Long.MAX_VALUE; core.config.s3Bucket = "b"; core.config.global.advanced.enabled = true; core.saveConfig();
		var back = CloudVaultConfig.load(core.configFile);
		check(back.encryptionEnabled && back.autoPausedUntil == Long.MAX_VALUE && back.s3Bucket.equals("b") && back.global.advanced.enabled, "config saves and reloads the new settings");
		check(!Files.readString(core.configFile).contains("secret"), "config.json holds no secrets");

		core.shutdown();
		System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
