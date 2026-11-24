import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.*;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.notification.NotificationSink;
import com.cloudvault.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Plain-Java smoke test of the engine (no Minecraft). Run: see devtests/README in DEVELOPMENT_NOTES.md */
public class CoreSmokeTest {
	static int fails = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

	static class FakeCloud implements CloudProvider {
		Map<String, byte[]> files = new LinkedHashMap<>(); boolean offline = false; int n = 0;
		public String id() { return "fake"; } public boolean isAuthenticated() { return true; }
		public void authenticate() {} public void signOut() {}
		public Status testConnection() { return new Status(true, "fake", "ok"); }
		public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException {
			if (offline) throw new CloudException(CloudException.Kind.NETWORK, "offline");
			byte[] b = Files.readAllBytes(f); String id = "r" + (++n); files.put(id, b);
			return new RemoteFile(id, name, b.length, HashUtil.hashFile(f).md5(), props);
		}
		public void downloadBackup(String id, Path t) throws IOException { Files.write(t, files.get(id)); }
		public List<RemoteFile> listBackups(String w) { return List.of(); }
		public void deleteBackup(String id) { files.remove(id); }
	}

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cvtest");
		Path world = tmp.resolve("saves/My World"); Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "LEVEL"); Files.writeString(world.resolve("region/r.0.0.mca"), "DATA1");
		Files.writeString(world.resolve("session.lock"), "lock");

		CloudVaultCore core = new CloudVaultCore(tmp.resolve("cloudvault"), NotificationSink.NONE);
		core.config.global.session.retention = 1; core.config.global.periodic.avoidDuplicates = true;
		FakeCloud cloud = new FakeCloud(); core.cloud.setProvider(cloud);
		String wid = core.worlds.idFor("My World");
		BackupManager.Request req = new BackupManager.Request(world, "My World", wid, BackupType.MANUAL, "26.3");

		// 1. manual backup -> local zip, verified, uploaded
		BackupMetadata m1 = core.backups.run(req);
		check(m1 != null && m1.localVerified && m1.sha256.length() == 64, "manual backup created with SHA-256");
		Thread.sleep(500); // worker uploads
		BackupMetadata r1 = core.store.find(m1.backupId).get();
		check(r1.uploadStatus == UploadStatus.UPLOADED_VERIFIED && r1.remoteId != null, "upload verified");
		try (ZipFile z = new ZipFile(core.store.zipPath(r1).toFile())) { check(z.getEntry("session.lock") == null && z.getEntry("level.dat") != null, "zip excludes session.lock, has level.dat"); }
		check(Files.readString(world.resolve("region/r.0.0.mca")).equals("DATA1"), "original world untouched");

		// 2. duplicate detection (periodic) + manual never skipped
		BackupManager.Request per = new BackupManager.Request(world, "My World", wid, BackupType.PERIODIC, "26.3");
		BackupMetadata p1 = core.backups.run(per); Thread.sleep(300);
		BackupMetadata p2 = core.backups.run(per);
		check(p1 != null && p2 == null, "periodic duplicate skipped when world unchanged");
		check(core.backups.run(req) != null, "manual backup not skipped");

		// 3. retention independent per category (session keep 1 previous => 2 kept)
		for (int i = 0; i < 4; i++) {
			Files.writeString(world.resolve("region/r.0.0.mca"), "DATA" + (i + 10));
			core.backups.run(new BackupManager.Request(world, "My World", wid, BackupType.SESSION_LEAVE, "26.3")); Thread.sleep(400);
		}
		long sess = core.store.forWorld(wid).stream().filter(m -> m.backupType.category == BackupType.Category.SESSION).count();
		long periodicCount = core.store.forWorld(wid).stream().filter(m -> m.backupType.category == BackupType.Category.PERIODIC).count();
		check(sess == 2, "session retention keeps newest + 1 previous (got " + sess + ")");
		check(periodicCount == 1, "session retention did not touch periodic");

		// 4. offline -> pending survives, then resumes
		cloud.offline = true; core.config.retryDelaySeconds = 3600;
		Files.writeString(world.resolve("region/r.0.0.mca"), "OFFLINE");
		BackupMetadata off = core.backups.run(req); Thread.sleep(500);
		check(core.store.find(off.backupId).get().uploadStatus == UploadStatus.PENDING && Files.exists(core.store.zipPath(off)), "offline backup stays local + pending");
		cloud.offline = false; core.cloud.resumePending(); Thread.sleep(600);
		check(core.store.find(off.backupId).get().uploadStatus == UploadStatus.UPLOADED_VERIFIED, "pending uploaded after resume");

		// 5. restore as new world + safety backup
		Path saves = tmp.resolve("saves");
		Path restored = core.restore.restoreAsNew(core.store.find(m1.backupId).get(), saves, req);
		check(restored.getFileName().toString().equals("My World - Restored") && Files.readString(restored.resolve("region/r.0.0.mca")).equals("DATA1"), "restored as new world with old data");
		check(core.store.forWorld(wid).stream().anyMatch(m -> m.backupType == BackupType.SAFETY), "safety backup created before restore");

		// 6. corrupted zip rejected, zip-slip rejected
		Path bad = tmp.resolve("evil.zip");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(bad))) { z.putNextEntry(new ZipEntry("../../evil.txt")); z.write(1); z.closeEntry(); }
		boolean blocked = false;
		try { var ex = Class.forName("com.cloudvault.restore.RestoreManager").getDeclaredMethod("extractSafely", Path.class, Path.class); ex.setAccessible(true); ex.invoke(null, bad, tmp.resolve("x")); }
		catch (java.lang.reflect.InvocationTargetException e) { blocked = e.getCause().getMessage().contains("Unsafe"); }
		check(blocked && !Files.exists(tmp.resolve("evil.txt")), "zip path traversal blocked");
		Path trunc = tmp.resolve("t.zip"); BackupMetadata keep = core.store.all().stream().filter(x -> x.localFilePresent && Files.exists(core.store.zipPath(x))).findFirst().get(); byte[] zb = Files.readAllBytes(core.store.zipPath(keep)); Files.write(trunc, Arrays.copyOf(zb, zb.length / 2));
		boolean corrupt = false; try { BackupVerifier.verifyZip(trunc); } catch (IOException e) { corrupt = true; }
		check(corrupt, "truncated zip detected");

		// 7. time maths
		ZonedDateTime jan31 = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, ZoneId.of("UTC"));
		check(TimeUtil.addInterval(jan31, 1, TimeUtil.Unit.MONTHS).toLocalDate().equals(LocalDate.of(2027, 2, 28)), "1 month from Jan 31 = Feb 28 (calendar-aware)");
		check(TimeUtil.addInterval(ZonedDateTime.of(2027, 2, 28, 0, 0, 0, 0, ZoneId.of("UTC")), 1, TimeUtil.Unit.YEARS).getYear() == 2028, "1 year");
		check(TimeUtil.parseAmount("abc", TimeUtil.Unit.HOURS) == -1 && TimeUtil.parseAmount("-3", TimeUtil.Unit.HOURS) == -1 && TimeUtil.parseAmount("99999999999", TimeUtil.Unit.HOURS) == -1, "invalid amounts rejected");
		BackupSettings.Periodic pp = new BackupSettings.Periodic(); pp.amount = 1; pp.unit = TimeUtil.Unit.HOURS;
		com.cloudvault.scheduler.PeriodicScheduler ps = new com.cloudvault.scheduler.PeriodicScheduler();
		ZonedDateTime now = ZonedDateTime.now(); ps.start(now.minusDays(5), pp, now);
		check(!ps.tick(pp, now) && ps.tick(pp, now.plusMinutes(2)) && !ps.tick(pp, now.plusMinutes(3)), "offline 5 days => exactly one catch-up backup");

		core.shutdown();
		System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
