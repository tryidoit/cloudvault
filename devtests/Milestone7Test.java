import com.cloudvault.CloudVaultCore;
import com.cloudvault.backup.*;
import com.cloudvault.cloud.Throttle;
import com.cloudvault.cloud.s3.SigV4;
import com.cloudvault.compare.BackupComparer;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.share.ShareManager;
import com.cloudvault.smart.SmartTriggers;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Round 8 engine: smart triggers, throttle, compare, share (+ SigV4 reference signature). Part 1; locks/alerts/mirror/account follow. */
public class Milestone7Test {
	static int failures = 0;
	static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) failures++; }
	static final Random R = new Random(8);
	static void rnd(Path f, int n) throws Exception { byte[] b = new byte[n]; R.nextBytes(b); Files.createDirectories(f.getParent()); Files.write(f, b); }
	static void world(Path d) throws Exception { Files.createDirectories(d); Files.writeString(d.resolve("level.dat"), "L0"); for (int i = 0; i < 6; i++) rnd(d.resolve("region/r." + i + ".mca"), 200_000); rnd(d.resolve("playerdata/p.dat"), 2000); }
	static BackupMetadata back(CloudVaultCore c, Path w, String id, BackupType t) { return c.backups.run(new BackupManager.Request(w, "W", id, t, "26.3")); }
	static long files(Path d) throws Exception { if (!Files.exists(d)) return 0; try (var st = Files.walk(d)) { return st.filter(Files::isRegularFile).count(); } }
	static Map<String, String> tree(Path root) throws Exception {
		Map<String, String> m = new TreeMap<>();
		try (var s = Files.walk(root)) { for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) m.put(root.relativize(p).toString().replace('\\', '/'), com.cloudvault.util.HashUtil.hashFile(p).sha256()); }
		return m;
	}
	static Map<String, String> zipTree(Path zip) throws Exception { Path t = Files.createTempDirectory("zt"); com.cloudvault.restore.RestoreManager.extractSafely(zip, t); return tree(t); }
	static boolean throwsIO(ThrowingRunnable r) { try { r.run(); return false; } catch (Exception e) { return true; } }
	interface ThrowingRunnable { void run() throws Exception; }

	public static void main(String[] a) throws Exception {
		Path tmp = Files.createTempDirectory("cv7");

		System.out.println("SigV4 pre-signed URL (AWS documented example)");
		String q = SigV4.presignQuery("GET", "/test.txt", "examplebucket.s3.amazonaws.com", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "20130524T000000Z", "us-east-1", "s3", 86400);
		check(q.endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"), "signature equals the one in the AWS documentation");

		System.out.println("Smart triggers");
		BackupSettings s = new BackupSettings(); s.smart.enabled = true; s.smart.cooldownMinutes = 10; s.smart.idle = true; s.smart.idleMinutes = 5;
		SmartTriggers t = new SmartTriggers(); long now = 1_000_000_000L; t.reset(now);
		now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:overworld", 10, true), now).isEmpty(), "first observation only records the dimension");
		now += 1000;
		var r = t.observe(s, new SmartTriggers.Observation("minecraft:the_nether", 10, true), now);
		check(r.isPresent() && r.get().contains("Nether"), "entering the Nether fires");
		now += 1000;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 10, true), now).isEmpty(), "cooldown blocks a second backup");
		now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 2, true), now).map(x -> x.contains("Low health")).orElse(false), "low health fires after the cooldown");
		now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 2, true), now).isEmpty(), "low health fires once per situation");
		now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 10, true), now).isEmpty(), "health recovered, nothing fires");
		now += 1000; t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 10, false), now);
		now += 6 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 10, false), now).map(x -> x.contains("Idle")).orElse(false), "standing still fires the idle trigger");
		now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:the_end", 10, false), now).isEmpty(), "idle fires once until the player moves");
		s.smart.enabled = false; now += 11 * 60_000L;
		check(t.observe(s, new SmartTriggers.Observation("minecraft:overworld", 10, true), now).isEmpty(), "switched off -> never fires");

		System.out.println("Throttle");
		Throttle.setLimitKBps(200);
		byte[] data = new byte[100 * 1024];
		long t0 = System.nanoTime();
		try (InputStream in = Throttle.limited(new ByteArrayInputStream(data))) { in.readAllBytes(); }
		long ms = (System.nanoTime() - t0) / 1_000_000;
		check(ms >= 350 && ms < 1500, "100 KB at 200 KB/s takes about half a second (" + ms + " ms)");
		Throttle.setLimitKBps(0);
		t0 = System.nanoTime();
		try (InputStream in = Throttle.limited(new ByteArrayInputStream(data))) { in.readAllBytes(); }
		check((System.nanoTime() - t0) / 1_000_000 < 200, "0 = unlimited");

		System.out.println("Compare two backups");
		CloudVaultCore c = new CloudVaultCore(tmp.resolve("cloudvault"), (l, tt, m) -> {});
		c.config.notifications = false; c.config.cloudEnabled = false; c.config.global.periodic.avoidDuplicates = false;
		Path w = tmp.resolve("saves/W"); world(w); String id = c.worlds.idFor("W");
		BackupMetadata b1 = back(c, w, id, BackupType.PERIODIC);
		Files.writeString(w.resolve("level.dat"), "L1"); rnd(w.resolve("region/r.0.mca"), 200_000); Files.writeString(w.resolve("new.txt"), "hi"); Files.delete(w.resolve("region/r.1.mca"));
		BackupMetadata b2 = back(c, w, id, BackupType.PERIODIC);
		check(b2 != null && b2.isDelta(), "second backup is incremental (compare must work on deltas)");
		var res = BackupComparer.compare(c.store, c.cloud, b1, b2, false);
		check(res.added().size() == 1 && res.added().get(0).path().equals("new.txt"), "added file found");
		check(res.removed().size() == 1 && res.removed().get(0).path().equals("region/r.1.mca"), "removed file found");
		check(res.changed().size() == 2, "two changed files (level.dat and r.0) -> " + res.changed().size());
		check(res.unchanged() == 5, "five unchanged files -> " + res.unchanged());
		var same = BackupComparer.compare(c.store, c.cloud, b2, b2, false);
		check(same.added().isEmpty() && same.removed().isEmpty() && same.changed().isEmpty(), "a backup compared with itself has no differences");
		check(!res.groups().isEmpty(), "grouped summary present");

		System.out.println("Share codes");
		Path zip = c.cloud.obtainVerifiedZip(b2);
		String sha = com.cloudvault.util.HashUtil.hashFile(zip).sha256();
		byte[] zb = Files.readAllBytes(zip);
		byte[] bad = zb.clone(); bad[bad.length / 2] ^= 0x55;
		HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		srv.createContext("/ok", ex -> { ex.sendResponseHeaders(200, zb.length); ex.getResponseBody().write(zb); ex.close(); });
		srv.createContext("/bad", ex -> { ex.sendResponseHeaders(200, bad.length); ex.getResponseBody().write(bad); ex.close(); });
		srv.createContext("/gone", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
		srv.start();
		String base = "http://127.0.0.1:" + srv.getAddress().getPort();
		String json = "{\"u\":\"" + base + "/ok\",\"n\":\"My world\",\"s\":" + zb.length + ",\"h\":\"" + sha + "\"}";
		String code = ShareManager.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		var p = ShareManager.parse(code);
		check(p.worldName().equals("My world") && p.size() == zb.length && sha.equals(p.sha256()), "share code parsed");
		Path got = ShareManager.download(p, tmp.resolve("dl"));
		check(Arrays.equals(Files.readAllBytes(got), zb), "download matches the original");
		var tampered = new ShareManager.Parsed(base + "/bad", "x", -1, sha);
		check(throwsIO(() -> ShareManager.download(tampered, tmp.resolve("dl"))), "tampered download is refused");
		check(throwsIO(() -> ShareManager.download(new ShareManager.Parsed(base + "/gone", "x", -1, null), tmp.resolve("dl"))), "expired share (404) gives an error");
		check(throwsIO(() -> ShareManager.parse("http://example.com/a.zip")), "plain http is refused");
		check(throwsIO(() -> ShareManager.parse(ShareManager.PREFIX + "!!!")), "garbage code is refused");
		check(ShareManager.parse("https://example.com/a.zip").url().startsWith("https://"), "a plain https link is accepted");
		srv.stop(0);

		System.out.println("World-in-use lock (two computers, one cloud)");
		Path cloudDir = tmp.resolve("lockcloud"); Files.createDirectories(cloudDir);
		CloudVaultCore ca = new CloudVaultCore(tmp.resolve("la"), (l, tt, m) -> {}), cb = new CloudVaultCore(tmp.resolve("lb"), (l, tt, m) -> {});
		for (CloudVaultCore x : List.of(ca, cb)) {
			x.config.notifications = false; x.config.cloudEnabled = true;
			var lp = new com.cloudvault.cloud.LocalFolderProvider("folder", () -> cloudDir.toString());
			x.cloud.registerProvider(lp); x.setProvider(lp);
		}
		ca.config.account.deviceName = "PC-A"; cb.config.account.deviceName = "PC-B";
		String sid = UUID.randomUUID().toString();
		check(ca.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.FREE, "nobody has the world -> FREE");
		ca.lock.acquire("Lockworld", sid);
		check(ca.lock.holding(), "A holds the lock");
		check(ca.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.FREE, "own lock never blocks the same computer");
		var held = cb.lock.check("Lockworld", sid);
		check(held.state() == com.cloudvault.lock.LockManager.State.HELD_BY_OTHER && held.device().contains("PC-A"), "B sees the world HELD by PC-A");
		check(cb.lock.check("Other world", UUID.randomUUID().toString()).state() == com.cloudvault.lock.LockManager.State.FREE, "another world is free");
		cb.config.lock.staleMinutes = 0; Thread.sleep(30);
		check(cb.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.STALE_OTHER, "old heartbeat -> STALE (other computer may not have finished)");
		cb.config.lock.staleMinutes = 10;
		ca.lock.release(true);
		check(!ca.lock.holding() && cb.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.FREE, "successful release frees the world");
		ca.lock.acquire("Lockworld", sid); ca.lock.release(false);
		check(cb.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.HELD_BY_OTHER, "failed leave-upload keeps the lock file (goes stale by itself)");
		cb.lock.acquire("Lockworld", sid);
		check(ca.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.HELD_BY_OTHER && ca.lock.check("Lockworld", sid).device().contains("PC-B"), "play anyway: the newest lock wins, A now sees B");
		cb.lock.release(true);
		cb.config.lock.enabled = false;
		check(cb.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.UNKNOWN, "lock switched off -> never blocks");
		cb.config.lock.enabled = true; cb.config.cloudEnabled = false;
		check(cb.lock.check("Lockworld", sid).state() == com.cloudvault.lock.LockManager.State.UNKNOWN, "cloud off/unreachable -> never blocks");
		ca.shutdown(); cb.shutdown();

		System.out.println("Alerts");
		List<String> hits = Collections.synchronizedList(new ArrayList<>());
		HttpServer as = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		for (String path : List.of("/discord", "/slack", "/hook", "/topic1"))
			as.createContext(path, ex -> { hits.add(path + "|" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); ex.sendResponseHeaders(200, -1); ex.close(); });
		as.start();
		String ab = "http://127.0.0.1:" + as.getAddress().getPort();
		CloudVaultCore al = new CloudVaultCore(tmp.resolve("alerts"), (l, tt, m) -> {});
		al.config.notifications = false; al.config.alerts.enabled = true; al.config.alerts.minMinutesBetween = 30;
		var chD = al.alerts.addChannel("discord"); al.alerts.setField(chD, "url", ab + "/discord/SECRETTOKEN123");
		var chS = al.alerts.addChannel("slack"); al.alerts.setField(chS, "url", ab + "/slack");
		var chW = al.alerts.addChannel("webhook"); al.alerts.setField(chW, "url", ab + "/hook");
		var chN = al.alerts.addChannel("ntfy"); al.alerts.setField(chN, "server", ab); al.alerts.setField(chN, "topic", "topic1");
		var chBad = al.alerts.addChannel("discord"); al.alerts.setField(chBad, "url", "http://example.com/hook");
		check(al.alerts.complete(chD) && al.alerts.complete(chN) && !al.alerts.complete(al.alerts.addChannel("telegram")), "complete() knows which channels are filled in");
		al.alerts.removeChannel(al.config.alerts.channels.get(al.config.alerts.channels.size() - 1));
		al.alerts.send(com.cloudvault.alerts.AlertManager.Event.BACKUP_FAILED, "Backup failed", "disk full");
		long end = System.currentTimeMillis() + 5000; while (hits.size() < 4 && System.currentTimeMillis() < end) Thread.sleep(50);
		check(hits.size() == 4 && hits.stream().anyMatch(x -> x.startsWith("/discord")) && hits.stream().anyMatch(x -> x.startsWith("/slack")) && hits.stream().anyMatch(x -> x.startsWith("/hook")) && hits.stream().anyMatch(x -> x.startsWith("/topic1")), "Discord, Slack, webhook and ntfy each got the alert (" + hits.size() + ")");
		check(hits.stream().allMatch(x -> x.contains("disk full") || x.contains("Backup failed")), "message arrives");
		hits.clear();
		al.alerts.send(com.cloudvault.alerts.AlertManager.Event.BACKUP_FAILED, "Backup failed", "disk full again"); Thread.sleep(400);
		check(hits.isEmpty(), "same alert within the minimum interval is not sent again");
		al.config.alerts.onUploadFailed = false;
		al.alerts.send(com.cloudvault.alerts.AlertManager.Event.UPLOAD_FAILED, "Upload failed", "x"); Thread.sleep(400);
		check(hits.isEmpty(), "event switched off -> nothing sent");
		al.config.alerts.enabled = false; al.config.alerts.onSyncProblem = true;
		al.alerts.send(com.cloudvault.alerts.AlertManager.Event.SYNC_PROBLEM, "Sync", "x"); Thread.sleep(400);
		check(hits.isEmpty(), "alerts off -> nothing sent");
		al.config.alerts.enabled = true; chD.enabled = false; chS.enabled = false; chW.enabled = false;
		String t1 = al.alerts.test(chN);
		check(t1.startsWith("Sent") && hits.size() == 1, "test button sends and reports (" + t1 + ")");
		String t2 = al.alerts.test(al.config.alerts.channels.get(0));
		check(t2.startsWith("Sent"), "test works even for a switched-off channel");
		al.saveConfig();
		String cfgText = Files.readString(al.configFile);
		check(!cfgText.contains("SECRETTOKEN123") && !cfgText.contains(ab), "config.json holds no webhook address or token");
		check(Files.exists(al.store.auth) && al.secrets.get("alert." + chD.id + ".url").contains("SECRETTOKEN123"), "secrets are in the secret store");
		check(al.alerts.test(al.alerts.addChannel("discord")).startsWith("Failed"), "an empty channel reports Failed instead of throwing");
		as.stop(0); al.shutdown();

		System.out.println("E-mail alert (fake SMTP server)");
		var smtp = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
		List<String> mail = Collections.synchronizedList(new ArrayList<>());
		Thread smtpT = new Thread(() -> { try (var sock = smtp.accept(); var in = new BufferedReader(new InputStreamReader(sock.getInputStream())); var out = new PrintWriter(sock.getOutputStream(), true)) {
			out.print("220 fake\r\n"); out.flush(); String line; boolean inData = false;
			while ((line = in.readLine()) != null) {
				if (inData) { if (line.equals(".")) { inData = false; out.print("250 queued\r\n"); out.flush(); } else mail.add(line); continue; }
				String u = line.toUpperCase();
				if (u.startsWith("EHLO") || u.startsWith("HELO")) out.print("250 localhost\r\n");
				else if (u.startsWith("DATA")) { inData = true; out.print("354 go\r\n"); }
				else if (u.startsWith("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
				else out.print("250 ok\r\n");
				if (u.startsWith("MAIL") || u.startsWith("RCPT")) mail.add(line);
				out.flush();
			}
		} catch (Exception ignored) { } }, "fake-smtp"); smtpT.setDaemon(true); smtpT.start();
		CloudVaultCore ml = new CloudVaultCore(tmp.resolve("mailcore"), (l, tt, m) -> {});
		ml.config.notifications = false; ml.config.alerts.enabled = true;
		var em = ml.alerts.addChannel("email");
		ml.alerts.setField(em, "host", "127.0.0.1"); ml.alerts.setField(em, "port", Integer.toString(smtp.getLocalPort())); ml.alerts.setField(em, "security", "none");
		ml.alerts.setField(em, "from", "cloudvault@example.test"); ml.alerts.setField(em, "to", "me@example.test");
		check(ml.alerts.complete(em), "e-mail channel complete without a login (optional fields)");
		String er = ml.alerts.test(em);
		smtpT.join(5000);
		check(er.startsWith("Sent"), "test e-mail accepted by the server (" + er + ")");
		check(mail.stream().anyMatch(x -> x.toLowerCase().contains("rcpt") && x.contains("me@example.test")) && mail.stream().anyMatch(x -> { try { return new String(Base64.getDecoder().decode(x), StandardCharsets.UTF_8).contains("test alert"); } catch (RuntimeException e) { return false; } }), "recipient and message text arrived");
		smtp.close(); ml.shutdown();

		System.out.println("Upload controls");
		CloudVaultCore uc = new CloudVaultCore(tmp.resolve("upl"), (l, tt, m) -> {});
		uc.config.notifications = false; uc.config.cloudEnabled = true; uc.config.global.periodic.avoidDuplicates = false;
		Path ucloud = tmp.resolve("uplcloud"); Files.createDirectories(ucloud);
		var ulp = new com.cloudvault.cloud.LocalFolderProvider("folder", () -> ucloud.toString()); uc.cloud.registerProvider(ulp); uc.setProvider(ulp);
		Path uw = tmp.resolve("saves/U"); world(uw); String uid = uc.worlds.idFor("U");
		uc.config.upload.paused = true;
		BackupMetadata u1 = uc.backups.run(new BackupManager.Request(uw, "U", uid, BackupType.PERIODIC, "26.3")); uc.queue.awaitIdle(10000);
		var u1m = uc.store.find(u1.backupId).get();
		check(u1m.uploadStatus == BackupMetadata.UploadStatus.PENDING && u1m.lastError != null && u1m.lastError.startsWith("Waiting:"), "paused -> backup is kept, upload PENDING with 'Waiting: ...' (" + u1m.lastError + ")");
		uc.config.upload.paused = false; uc.cloud.kickWaiting(); uc.queue.awaitIdle(15000);
		check(uc.store.find(u1.backupId).get().uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED, "resume + kickWaiting uploads it");
		int h = java.time.LocalTime.now().getHour();
		uc.config.upload.windowEnabled = true; uc.config.upload.windowStartHour = (h + 2) % 24; uc.config.upload.windowEndHour = (h + 4) % 24;
		check(uc.cloud.uploadHold() != null && uc.cloud.uploadHold().contains("outside"), "outside the hours window -> on hold");
		uc.config.upload.windowStartHour = h; uc.config.upload.windowEndHour = (h + 1) % 24;
		check(uc.cloud.uploadHold() == null, "inside the window -> may upload");
		uc.config.upload.windowStartHour = (h + 22) % 24; uc.config.upload.windowEndHour = (h + 2) % 24;
		check(uc.cloud.uploadHold() == null, "window over midnight is handled");
		uc.config.upload.windowEnabled = false;
		uc.config.upload.onlyWhenNotPlaying = true;
		check(uc.cloud.uploadHold() == null, "not playing -> may upload");
		uc.config.global.session.joinEnabled = false; uc.config.global.session.leaveEnabled = false; uc.config.global.periodic.enabled = false; uc.config.global.gameplay.enabled = false;
		uc.onWorldJoined(uw, "U", "U", "26.3");
		check(uc.cloud.uploadHold() != null && uc.cloud.uploadHold().contains("playing"), "playing + only-when-not-playing -> on hold");
		uc.onWorldLeaving();
		check(uc.cloud.uploadHold() == null, "after leaving the world uploads may go again");
		uc.shutdown();

		System.out.println("Second destination (mirror)");
		Path c1 = tmp.resolve("m-primary"), c2 = tmp.resolve("m-second"); Files.createDirectories(c1); Files.createDirectories(c2);
		CloudVaultCore mc = new CloudVaultCore(tmp.resolve("mir"), (l, tt, m) -> {});
		mc.config.notifications = false; mc.config.cloudEnabled = true; mc.config.localCacheKeep = 1; mc.config.global.periodic.avoidDuplicates = false;
		mc.config.mirror.enabled = true; mc.config.mirror.type = "local"; mc.config.mirror.localPath = c2.toString();
		mc.mirror.setProvider(com.cloudvault.mirror.MirrorManager.build(mc.config, mc.secrets));
		var mlp = new com.cloudvault.cloud.LocalFolderProvider("folder", () -> c1.toString()); mc.cloud.registerProvider(mlp); mc.setProvider(mlp);
		Path mw = tmp.resolve("saves/M"); world(mw); String mid = mc.worlds.idFor("M");
		BackupMetadata m1 = mc.backups.run(new BackupManager.Request(mw, "M", mid, BackupType.PERIODIC, "26.3")); mc.queue.awaitIdle(30000);
		rnd(mw.resolve("region/r.0.mca"), 200_000);
		BackupMetadata m2 = mc.backups.run(new BackupManager.Request(mw, "M", mid, BackupType.PERIODIC, "26.3")); mc.queue.awaitIdle(30000);
		check(m2.isDelta(), "second backup is incremental");
		check(mc.store.forWorld(mid).stream().allMatch(x -> x.mirrorRemoteId != null && x.uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED), "full and incremental backups reached BOTH destinations");
		check(files(c1) == files(c2) && files(c2) >= 2, "both folders hold the same number of files (" + files(c1) + "/" + files(c2) + ")");
		// restore only from the second copy
		for (var x : mc.store.forWorld(mid)) { Files.deleteIfExists(mc.store.zipPath(x)); var y = x.copy(); y.localFilePresent = false; mc.store.save(y); }
		try (var st = Files.walk(c1)) { for (Path f : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator) Files.delete(f); }
		check(zipTree(mc.cloud.obtainVerifiedZip(mc.store.find(m2.backupId).get())).equals(tree(mw)), "first destination lost everything -> restore (incl. incremental chain) works from the second copy");
		// unreachable mirror does not disturb the primary
		Path blocker = tmp.resolve("blocker"); Files.writeString(blocker, "x");
		mc.config.mirror.localPath = blocker.resolve("sub").toString();
		rnd(mw.resolve("region/r.1.mca"), 200_000);
		BackupMetadata m3 = mc.backups.run(new BackupManager.Request(mw, "M", mid, BackupType.PERIODIC, "26.3")); mc.queue.awaitIdle(30000);
		var m3m = mc.store.find(m3.backupId).get();
		check(m3m.uploadStatus == BackupMetadata.UploadStatus.UPLOADED_VERIFIED, "unreachable second destination: first upload still succeeds");
		check(m3m.mirrorRemoteId == null && m3m.mirrorError != null && m3m.lastError == null, "mirror error is recorded separately (" + m3m.mirrorError + ")");
		// backfill when it becomes reachable again
		mc.config.mirror.localPath = c2.toString();
		mc.mirror.backfill(); mc.queue.awaitIdle(30000);
		long deadline = System.currentTimeMillis() + 10000; while (mc.store.find(m3.backupId).get().mirrorRemoteId == null && System.currentTimeMillis() < deadline) { Thread.sleep(100); mc.queue.awaitIdle(5000); }
		check(mc.store.find(m3.backupId).get().mirrorRemoteId != null, "backfill copies the missing backup once the destination works again");
		// deleting removes both copies
		long before = files(c2);
		var del = mc.store.find(m3.backupId).get();
		mc.cloud.deleteBackup(del);
		check(files(c2) < before, "deleting a backup also deletes its second copy (" + before + " -> " + files(c2) + ")");
		mc.shutdown();

		System.out.println("CloudVault account (settings file in the cloud)");
		Path acloud = tmp.resolve("acloud"); Files.createDirectories(acloud);
		CloudVaultCore xa = new CloudVaultCore(tmp.resolve("acA"), (l, tt, m) -> {}), xb = new CloudVaultCore(tmp.resolve("acB"), (l, tt, m) -> {});
		for (CloudVaultCore x : List.of(xa, xb)) { x.config.notifications = false; x.config.cloudEnabled = true; var lp = new com.cloudvault.cloud.LocalFolderProvider("folder", () -> acloud.toString()); x.cloud.registerProvider(lp); x.setProvider(lp); }
		xa.config.account.deviceName = "Alpha PC"; xb.config.account.deviceName = "Beta Laptop";
		xa.config.global.smart.cooldownMinutes = 77; xa.config.alerts.noBackupDays = 9; xa.config.global.incremental.maxChain = 3;
		var ach = xa.alerts.addChannel("discord"); xa.alerts.setField(ach, "url", "https://discord.com/api/webhooks/1/ACCOUNTSECRET999");
		xa.config.mirror.enabled = true; xa.config.mirror.localPath = tmp.resolve("somewhere").toString();
		xa.account.enable();
		var pa = xa.account.listProfiles();
		check(pa.size() == 1 && pa.get(0).mine() && pa.get(0).fileName().contains("Alpha") && pa.get(0).fileName().contains(xa.config.account.deviceId.substring(0, 8)), "file name has device name and id: " + pa.get(0).fileName());
		check(!pa.get(0).hasSecrets(), "no account password -> no secrets in the file");
		String raw = new String(Files.readAllBytes(Files.walk(acloud).filter(f -> f.getFileName().toString().startsWith("settings_")).findFirst().get()), StandardCharsets.UTF_8);
		check(!raw.contains("ACCOUNTSECRET999") && !raw.contains("\"worlds\": {\"") , "file contains no webhook secret");
		xa.account.setPassword("hunter2-pw"); xa.account.uploadNow(); xa.account.uploadNow();
		var pa2 = xa.account.listProfiles();
		check(pa2.size() == 1 && pa2.get(0).hasSecrets(), "own file is refreshed, not duplicated; now with secrets (" + pa2.size() + ")");
		raw = new String(Files.readAllBytes(Files.walk(acloud).filter(f -> f.getFileName().toString().startsWith("settings_")).findFirst().get()), StandardCharsets.UTF_8);
		check(!raw.contains("ACCOUNTSECRET999"), "secrets are encrypted inside the file");
		var pb = xb.account.listProfiles();
		check(pb.size() == 1 && !pb.get(0).mine() && pb.get(0).deviceName().contains("Alpha"), "new device finds the other computer's file");
		var r0 = xb.account.applyProfile(pb.get(0), null);
		check(!r0.ok() && r0.needsPassword(), "settings with secrets ask for the password");
		var r1 = xb.account.applyProfile(pb.get(0), "wrong");
		check(!r1.ok() && r1.needsPassword(), "wrong password refused: " + r1.message());
		check(xb.config.global.smart.cooldownMinutes != 77, "nothing applied after a refusal");
		String keepProvider = xb.config.cloudProvider;
		var r2 = xb.account.applyProfile(pb.get(0), "hunter2-pw");
		check(r2.ok(), "right password applies: " + r2.message());
		check(xb.config.global.smart.cooldownMinutes == 77 && xb.config.alerts.noBackupDays == 9 && xb.config.global.incremental.maxChain == 3, "settings arrived (smart, alerts, incremental)");
		check(xb.config.alerts.channels.size() == 1 && xb.alerts.field(xb.config.alerts.channels.get(0), "url").contains("ACCOUNTSECRET999"), "alert channel and its secret arrived");
		check(xb.config.mirror.enabled && xb.config.mirror.localPath.contains("somewhere"), "second destination arrived");
		check(java.util.Objects.equals(keepProvider, xb.config.cloudProvider) && xb.config.account.deviceName.equals("Beta Laptop") && !xb.config.account.deviceId.equals(xa.config.account.deviceId), "this computer keeps its own cloud login and identity");
		check(xb.config.account.enabled && xb.account.password().equals("hunter2-pw"), "account is on for the new device");
		xb.config.global.smart.cooldownMinutes = 33; xb.account.markDirty();
		long dl = System.currentTimeMillis() + 15000; List<com.cloudvault.account.AccountManager.Profile> pl = List.of();
		while (System.currentTimeMillis() < dl) { pl = xb.account.listProfiles(); if (pl.size() == 2) break; Thread.sleep(500); }
		check(pl.size() == 2 && pl.stream().anyMatch(x -> x.mine() && x.fileName().contains("Beta")), "local change creates this device's own file automatically");
		xb.account.uploadNow();
		check(xb.account.listProfiles().size() == 2, "another change refreshes it, no third file");
		xb.account.disable(true);
		check(xa.account.listProfiles().size() == 1 && !xb.config.account.enabled, "turning the account off removes this device's file");
		xa.shutdown(); xb.shutdown();

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		c.shutdown();
		System.exit(failures == 0 ? 0 : 1);
	}
}
