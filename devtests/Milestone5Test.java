import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.BackupManager;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupType;
import com.cloudvault.cloud.CloudException;
import com.cloudvault.cloud.CloudProvider;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** Cloud sync (two computers), comments inside the backup zip, and real world import. Uses a folder as a fake cloud. */
public class Milestone5Test {
	static int failures = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) failures++; }

	/** A cloud that is just a directory; ids are file paths. */
	static class FolderCloud implements CloudProvider {
		final Path dir; final Gson gson = new Gson();
		FolderCloud(Path dir) { this.dir = dir; }
		public String id() { return "folder"; }
		public boolean isAuthenticated() { return true; }
		public void authenticate() {}
		public void signOut() {}
		public Status testConnection() { return new Status(true, "folder", ""); }
		public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException { return put("CloudVault", f, name, folder, props); }
		public RemoteFile uploadSync(Path f, String name, String folder, Map<String, String> props) throws IOException { return put("CloudVault Sync", f, name, folder, props); }
		RemoteFile put(String root, Path f, String name, String folder, Map<String, String> props) throws IOException {
			Path d = dir.resolve(root).resolve(folder); Files.createDirectories(d);
			Files.copy(f, d.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(d.resolve(name + ".props"), gson.toJson(props));
			return remote(d.resolve(name), root, folder);
		}
		@SuppressWarnings("unchecked") RemoteFile remote(Path p, String root, String folder) throws IOException {
			Map<String, String> props = new HashMap<>();
			Path pp = p.resolveSibling(p.getFileName() + ".props");
			if (Files.exists(pp)) props.putAll(gson.fromJson(Files.readString(pp), Map.class));
			props.put("cv_folder", folder);
			return new RemoteFile(dir.relativize(p).toString(), p.getFileName().toString(), Files.size(p), com.cloudvault.util.HashUtil.hashFile(p).md5(), props);
		}
		public void downloadBackup(String id, Path target) throws IOException { Files.createDirectories(target.getParent()); Files.copy(dir.resolve(id), target, StandardCopyOption.REPLACE_EXISTING); }
		public List<RemoteFile> listBackups(String folder) throws IOException { return list("CloudVault", "backup-"); }
		public List<RemoteFile> listSync() throws IOException { return list("CloudVault Sync", "sync-"); }
		List<RemoteFile> list(String root, String prefix) throws IOException {
			List<RemoteFile> out = new ArrayList<>();
			Path r = dir.resolve(root);
			if (!Files.isDirectory(r)) return out;
			try (Stream<Path> s = Files.walk(r)) {
				for (Path p : (Iterable<Path>) s::iterator)
					if (Files.isRegularFile(p) && p.getFileName().toString().startsWith(prefix) && !p.getFileName().toString().endsWith(".props")) out.add(remote(p, root, p.getParent().getFileName().toString()));
			}
			return out;
		}
		public void deleteBackup(String id) throws IOException { Files.deleteIfExists(dir.resolve(id)); Files.deleteIfExists(dir.resolve(id + ".props")); }
		long count() throws IOException { return listSync().size(); }
	}

	static void world(Path dir, String content) throws IOException {
		Files.createDirectories(dir.resolve("region"));
		Files.writeString(dir.resolve("level.dat"), "level-" + content);
		Files.writeString(dir.resolve("region/r.0.0.mca"), content);
	}
	static String read(Path p) throws IOException { return Files.readString(p); }

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cv5");
		FolderCloud cloud = new FolderCloud(tmp.resolve("cloud"));
		Path rootA = tmp.resolve("A/cloudvault"), rootB = tmp.resolve("B/cloudvault"), savesA = tmp.resolve("A/saves"), savesB = tmp.resolve("B/saves");
		CloudVaultCore A = new CloudVaultCore(rootA, (l, t, m) -> {}), B = new CloudVaultCore(rootB, (l, t, m) -> {});
		A.cloud.registerProvider(cloud); A.setProvider(cloud); B.cloud.registerProvider(cloud); B.setProvider(cloud);
		A.config.notifications = false; B.config.notifications = false;

		System.out.println("Cloud sync");
		Path wa = savesA.resolve("My World"); world(wa, "v1");
		String idA = A.worlds.idFor("My World");
		check(!A.config.isWorldSynced(idA), "sync is OFF by default for a world");
		check(A.config.sync.keep == 2, "default copies kept = 2");
		A.sync.requestPush(idA, "My World", wa);
		check(cloud.count() == 0, "nothing is uploaded while sync is off");
		A.config.worldSettings(idA).syncEnabled = true;
		check(A.config.isWorldSynced(idA), "sync can be switched ON for one world");
		check(A.sync.push(idA, "My World", wa), "push 1 ok");
		check(cloud.count() == 1, "1 copy in the cloud sync folder");
		Thread.sleep(1100); world(wa, "v2"); check(A.sync.push(idA, "My World", wa), "push 2 ok");
		Thread.sleep(1100); world(wa, "v3"); check(A.sync.push(idA, "My World", wa), "push 3 ok");
		check(cloud.count() == 2, "keeps the newest 2 copies (3 uploads -> " + cloud.count() + ")");
		A.config.worldSettings(idA).syncKeep = 3;
		check(A.config.syncKeep(idA) == 3, "per-world number overrides the default");
		A.config.worldSettings(idA).syncKeep = null;
		check(Files.list(cloud.dir.resolve("CloudVault Sync")).count() == 1, "sync lives in its own folder, one sub-folder per world");
		check(!A.config.worldSettings(idA).syncDirty, "not marked as waiting after a successful upload");

		System.out.println("Second computer");
		check(B.config.sync.autoDownload, "auto-download is on by default");
		var r1 = B.sync.pullAll(savesB, p -> false);
		check(r1.downloaded() == 1 && r1.error() == null, "B adds the world it does not have: " + r1.summary());
		Path wb = savesB.resolve("My World");
		check(Files.isRegularFile(wb.resolve("level.dat")) && read(wb.resolve("region/r.0.0.mca")).equals("v3"), "B has the same content as A");
		String idB = B.worlds.idIfKnown("My World");
		check(idB != null && B.config.isWorldSynced(idB), "the world is synced on B too");
		check(B.config.worldSettings(idB).syncId.equals(A.config.worldSettings(idA).syncId), "same sync id on both computers");
		var r1b = B.sync.pullAll(savesB, p -> false);
		check(r1b.downloaded() + r1b.updated() + r1b.copies() == 0, "second check changes nothing");

		System.out.println("Newer in the cloud");
		Thread.sleep(1100); world(wa, "v4"); A.sync.push(idA, "My World", wa);
		var r2 = B.sync.pullAll(savesB, p -> true);
		check(r2.updated() == 0 && r2.skipped() == 1, "an open world is left alone");
		var r3 = B.sync.pullAll(savesB, p -> false);
		check(r3.updated() == 1, "B updates its older, unchanged world: " + r3.summary());
		check(read(wb.resolve("region/r.0.0.mca")).equals("v4"), "B now has v4");
		check(Files.isDirectory(rootB.resolve("replaced")) && Files.list(rootB.resolve("replaced")).count() == 1, "old folder kept in cloudvault/replaced");

		System.out.println("Changed on both computers");
		Files.writeString(wb.resolve("region/r.0.0.mca"), "B-local");
		Thread.sleep(1100); world(wa, "v5"); A.sync.push(idA, "My World", wa);
		var r4 = B.sync.pullAll(savesB, p -> false);
		check(r4.copies() == 1, "cloud version added as a copy: " + r4.summary());
		check(read(wb.resolve("region/r.0.0.mca")).equals("B-local"), "B's own world was NOT overwritten");
		check(read(savesB.resolve("My World (cloud copy)/region/r.0.0.mca")).equals("v5"), "the copy holds the cloud version");
		var r5 = B.sync.pullAll(savesB, p -> false);
		check(r5.copies() == 0, "the copy is not added again at the next start");

		System.out.println("World deleted on purpose / offline retry");
		Path gone = savesB.resolve("My World (cloud copy)");
		check(B.config.worldSettings(B.worlds.idIfKnown("My World (cloud copy)")).syncEnabled == Boolean.FALSE, "the copy is not synced (separate world)");
		B.config.worldSettings(idB).syncDirty = true;
		check(B.config.worldSettings(idB).syncDirty, "a failed upload stays marked as waiting");

		System.out.println("Comments");
		A.config.global.session.joinComment = true;
		check(A.config.effective(idA).commentFor(BackupType.SESSION_JOIN) && !A.config.effective(idA).commentFor(BackupType.SESSION_LEAVE), "join and leave comments are separate switches");
		check(!A.config.global.commentFor(BackupType.SAFETY) && !A.config.global.commentFor(BackupType.PRE_UPDATE), "safety backups never ask");
		A.config.worldSettings(idA).useOverride = true; A.config.worldSettings(idA).settings.manual.comment = true;
		check(A.config.effective(idA).commentFor(BackupType.MANUAL) && !A.config.global.commentFor(BackupType.MANUAL), "a world can have its own comment settings");
		A.config.cloudEnabled = false; // keep this local: only the zip is inspected
		BackupMetadata m = A.backups.run(new BackupManager.Request(wa, "My World", idA, BackupType.MANUAL, "26.3").withComment("Before the dragon fight\nsecond line"));
		check(m != null && m.comment != null && m.comment.startsWith("Before the dragon"), "comment stored with the backup record");
		try (ZipFile z = new ZipFile(A.store.zipPath(m).toFile())) {
			var e = z.getEntry(BackupManager.COMMENT_FILE);
			check(e != null && new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).startsWith("Before the dragon"), "comment is inside the backup zip");
		}
		Path restored = A.restore.extractAsNewWorld(A.store.zipPath(m), "Restored", tmp.resolve("A/saves2"));
		check(Files.isRegularFile(restored.resolve("level.dat")) && !Files.exists(restored.resolve(BackupManager.COMMENT_FILE)), "restoring does not put the comment file into the world");
		BackupMetadata m2 = A.backups.run(new BackupManager.Request(wa, "My World", idA, BackupType.MANUAL, "26.3"));
		check(m2 != null && m2.comment == null, "no comment -> nothing added");
		CloudVaultCore Re = new CloudVaultCore(rootA, (l, t, mm) -> {});
		check(Re.store.find(m.backupId).map(x -> x.comment != null).orElse(false), "comment survives a restart");

		System.out.println("Import");
		int before = A.store.all().size();
		CloudProvider.RemoteFile f = cloud.listSync().get(0);
		A.cloud.setProvider(cloud);
		Path imp = A.importWorldFromCloud(f, "Imported One", tmp.resolve("C/saves"));
		check(Files.isRegularFile(imp.resolve("level.dat")), "world imported into saves as a new world");
		check(A.store.all().size() == before, "import records nothing else (no backup entries)");
		Path imp2 = A.importWorldFromCloud(f, "Imported One", tmp.resolve("C/saves"));
		check(!imp2.equals(imp) && Files.isRegularFile(imp2.resolve("level.dat")), "importing twice makes a second folder, never overwrites");
		check(Files.list(rootA.resolve("temp")).count() == 0, "no temp files left");

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		A.shutdown(); B.shutdown(); Re.shutdown();
		System.exit(failures == 0 ? 0 : 1);
	}
}
