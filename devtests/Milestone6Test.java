import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.cloud.LocalFolderProvider;
import com.cloudvault.incremental.Manifest;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Incremental backups: correctness, fallbacks, retention safety, damaged chains. */
public class Milestone6Test {
	static int failures = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) failures++; }
	static final Random R = new Random(7);

	static void rnd(Path f, int n) throws Exception { byte[] b = new byte[n]; R.nextBytes(b); Files.createDirectories(f.getParent()); Files.write(f, b); }
	static void world(Path d) throws Exception { Files.createDirectories(d); Files.writeString(d.resolve("level.dat"), "L0"); for (int i = 0; i < 6; i++) rnd(d.resolve("region/r." + i + ".mca"), 300_000); rnd(d.resolve("playerdata/p.dat"), 2000); }

	static Map<String, String> tree(Path root) throws Exception {
		Map<String, String> m = new TreeMap<>();
		try (var s = Files.walk(root)) { for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) m.put(root.relativize(p).toString().replace('\\', '/'), com.cloudvault.util.HashUtil.hashFile(p).sha256()); }
		return m;
	}
	static Map<String, String> zipTree(Path zip) throws Exception {
		Path tmp = Files.createTempDirectory("zt");
		com.cloudvault.restore.RestoreManager.extractSafely(zip, tmp);
		return tree(tmp);
	}

	static BackupMetadata back(CloudVaultCore c, Path w, String id, BackupType t) { return c.backups.run(new BackupManager.Request(w, "W", id, t, "26.3")); }

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cv6");
		CloudVaultCore c = new CloudVaultCore(tmp.resolve("cloudvault"), (l, t, m) -> {});
		c.config.notifications = false; c.config.cloudEnabled = false;
		Path w = tmp.resolve("saves/W"); world(w);
		String id = c.worlds.idFor("W");
		c.config.global.periodic.avoidDuplicates = false;

		System.out.println("Full then incremental");
		BackupMetadata b1 = back(c, w, id, BackupType.PERIODIC);
		check(b1 != null && !b1.isDelta() && c.store.loadManifest(b1.backupId) != null, "first backup is full and records its file list");
		try (ZipFile z = new ZipFile(c.store.zipPath(b1).toFile())) { check(z.getEntry(Manifest.ZIP_ENTRY) != null, "file list is inside the zip too"); }
		Files.writeString(w.resolve("level.dat"), "L1"); rnd(w.resolve("region/r.0.mca"), 300_000); Files.writeString(w.resolve("new.txt"), "hi");
		BackupMetadata b2 = back(c, w, id, BackupType.PERIODIC);
		check(b2 != null && b2.isDelta() && b2.parentId.equals(b1.backupId) && b2.dependsOn.equals(List.of(b1.backupId)), "second backup is incremental, depends on the first");
		check(c.store.zipPath(b2).getFileName().toString().endsWith(".delta.zip"), "incremental file name says so");
		check(Files.size(c.store.zipPath(b2)) < Files.size(c.store.zipPath(b1)) / 2, "incremental zip is much smaller (" + Files.size(c.store.zipPath(b2)) + " vs " + Files.size(c.store.zipPath(b1)) + ")");
		Path full2 = c.cloud.obtainVerifiedZip(b2);
		check(zipTree(full2).equals(tree(w)), "restoring the incremental backup gives exactly the world (every file identical)");

		System.out.println("Deleted and changed files");
		Files.delete(w.resolve("region/r.1.mca")); Files.delete(w.resolve("new.txt")); rnd(w.resolve("region/r.2.mca"), 300_000); Files.writeString(w.resolve("level.dat"), "L2");
		BackupMetadata b3 = back(c, w, id, BackupType.PERIODIC);
		check(b3.isDelta() && b3.chainDepth == 2, "third is incremental (chain depth 2)");
		Map<String, String> got = zipTree(c.cloud.obtainVerifiedZip(b3));
		check(got.equals(tree(w)) && !got.containsKey("region/r.1.mca") && !got.containsKey("new.txt"), "deleted files stay deleted");
		check(zipTree(c.cloud.obtainVerifiedZip(b2)).containsKey("new.txt"), "an older incremental backup still has its own files");

		System.out.println("Fallbacks to a full backup");
		for (int i = 0; i < 6; i++) rnd(w.resolve("region/r." + i + ".mca"), 300_000);
		BackupMetadata b4 = back(c, w, id, BackupType.PERIODIC);
		check(!b4.isDelta(), "most of the world changed -> full backup");
		c.config.global.incremental.enabled = false; rnd(w.resolve("region/r.0.mca"), 300_000);
		BackupMetadata b5 = back(c, w, id, BackupType.PERIODIC);
		check(!b5.isDelta() && c.store.loadManifest(b5.backupId) == null, "switched off -> plain full backup, as before");
		c.config.global.incremental.enabled = true; rnd(w.resolve("region/r.0.mca"), 300_000);
		BackupMetadata b6 = back(c, w, id, BackupType.PERIODIC);
		check(!b6.isDelta(), "previous backup has no file list -> full (never guesses)");
		rnd(w.resolve("region/r.0.mca"), 300_000);
		BackupMetadata b7 = back(c, w, id, BackupType.PERIODIC);
		check(b7.isDelta(), "next one is incremental again");
		BackupMetadata s1 = back(c, w, id, BackupType.SAFETY);
		check(!s1.isDelta(), "safety backups are always full");
		BackupMetadata m1 = back(c, w, id, BackupType.MANUAL);
		check(!m1.isDelta(), "first manual backup is full (chains never cross backup types)");

		System.out.println("Chain length and age");
		c.config.global.incremental.maxChain = 2; c.config.global.periodic.retention = 20;
		Path w2 = tmp.resolve("saves/W2"); world(w2); String id2 = c.worlds.idFor("W2");
		List<BackupMetadata> ch = new ArrayList<>();
		for (int i = 0; i < 5; i++) { rnd(w2.resolve("region/r.0.mca"), 300_000); ch.add(c.backups.run(new BackupManager.Request(w2, "W2", id2, BackupType.PERIODIC, "26.3"))); }
		check(!ch.get(0).isDelta() && ch.get(1).isDelta() && ch.get(2).isDelta() && !ch.get(3).isDelta() && ch.get(4).isDelta(), "no more than 2 incremental backups after a full one");
		c.config.global.incremental.maxChain = 6;
		BackupMetadata oldBase = c.store.find(ch.get(4).backupId).get(); oldBase.baseTimestamp -= 8L * 86_400_000L; c.store.save(oldBase);
		rnd(w2.resolve("region/r.0.mca"), 300_000);
		BackupMetadata aged = c.backups.run(new BackupManager.Request(w2, "W2", id2, BackupType.PERIODIC, "26.3"));
		check(aged != null && !aged.isDelta(), "full backup older than 7 days -> a fresh full one");

		System.out.println("Retention never breaks a chain");
		Path w3 = tmp.resolve("saves/W3"); world(w3); String id3 = c.worlds.idFor("W3");
		c.config.global.periodic.retention = 2; // keeps 3 restore points
		c.config.global.incremental.maxChain = 10;
		for (int i = 0; i < 12; i++) { rnd(w3.resolve("region/r." + (i % 6) + ".mca"), 300_000); Files.writeString(w3.resolve("level.dat"), "v" + i); c.backups.run(new BackupManager.Request(w3, "W3", id3, BackupType.PERIODIC, "26.3")); c.retention.apply(id3, BackupType.Category.PERIODIC, 2, c.config.global.advanced, null); }
		List<BackupMetadata> left = c.store.forWorld(id3);
		boolean allRestorable = true; Set<String> ids = new HashSet<>(); for (var m : left) ids.add(m.backupId);
		for (var m : left) { if (m.dependsOn != null) for (String d : m.dependsOn) if (!ids.contains(d)) allRestorable = false; }
		check(allRestorable, "every kept backup still has everything it needs (" + left.size() + " kept)");
		check(left.size() >= 3, "at least the 3 requested restore points are kept");
		BackupMetadata newest = left.get(0);
		check(zipTree(c.cloud.obtainVerifiedZip(newest)).equals(tree(w3)), "newest backup restores exactly");
		long chainBytes = left.stream().mapToLong(m -> m.fileSize).sum();
		check(chainBytes < 3L * 6 * 300_000, "space used (" + chainBytes + ") is less than 3 full backups would use");
		BackupMetadata base = left.stream().filter(m -> c.store.isRequired(m.backupId)).findFirst().orElse(null);
		if (base != null) { try { c.cloud.deleteBackup(base); check(false, "deleting a needed backup is refused"); } catch (java.io.IOException e) { check(true, "deleting a backup that others need is refused"); } }

		System.out.println("Damaged chain");
		BackupMetadata tip = left.get(0);
		if (tip.isDelta() && tip.dependsOn != null && !tip.dependsOn.isEmpty()) {
			BackupMetadata dep = c.store.find(tip.dependsOn.get(0)).get();
			byte[] zb = Files.readAllBytes(c.store.zipPath(dep)); zb[zb.length / 2] ^= 0x55; Files.write(c.store.zipPath(dep), zb);
			try { c.cloud.obtainVerifiedZip(tip); check(false, "damaged earlier backup is detected"); }
			catch (java.io.IOException e) { check(true, "damaged earlier backup is detected, nothing is restored: " + e.getMessage()); }
		} else check(true, "(newest is full in this run)");

		System.out.println("With cloud (folder), trimming and re-download");
		CloudVaultCore c2 = new CloudVaultCore(tmp.resolve("cv2"), (l, t, m) -> {});
		c2.config.notifications = false; c2.config.cloudEnabled = true; c2.config.localCacheKeep = 1;
		LocalFolderProvider lp = new LocalFolderProvider("folder", () -> tmp.resolve("cloud").toString()); Files.createDirectories(tmp.resolve("cloud"));
		c2.cloud.registerProvider(lp); c2.setProvider(lp);
		Path w4 = tmp.resolve("saves/W4"); world(w4); String id4 = c2.worlds.idFor("W4");
		c2.config.global.periodic.avoidDuplicates = false;
		BackupMetadata f1 = c2.backups.run(new BackupManager.Request(w4, "W4", id4, BackupType.PERIODIC, "26.3")); c2.queue.awaitIdle(30000);
		rnd(w4.resolve("region/r.0.mca"), 300_000);
		BackupMetadata d1 = c2.backups.run(new BackupManager.Request(w4, "W4", id4, BackupType.PERIODIC, "26.3")); c2.queue.awaitIdle(30000);
		rnd(w4.resolve("region/r.1.mca"), 300_000);
		BackupMetadata d2 = c2.backups.run(new BackupManager.Request(w4, "W4", id4, BackupType.PERIODIC, "26.3")); c2.queue.awaitIdle(30000);
		var all4 = c2.store.forWorld(id4);
		check(all4.stream().allMatch(m -> m.uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED), "all three uploaded and verified (full + 2 incremental)");
		check(d2.isDelta() && Files.exists(c2.store.zipPath(c2.store.find(f1.backupId).get())), "trimming keeps the full backup the newest one needs");
		for (var m : c2.store.forWorld(id4)) { Files.deleteIfExists(c2.store.zipPath(m)); var x = m.copy(); x.localFilePresent = false; c2.store.save(x); }
		check(zipTree(c2.cloud.obtainVerifiedZip(c2.store.find(d2.backupId).get())).equals(tree(w4)), "newest incremental backup restores from the cloud alone");
		check(c2.store.find(d1.backupId).isPresent(), "records intact");

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		c.shutdown(); c2.shutdown();
		System.exit(failures == 0 ? 0 : 1);
	}
}
