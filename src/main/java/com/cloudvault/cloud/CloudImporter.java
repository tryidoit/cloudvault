package com.cloudvault.cloud;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.backup.CloudFileName;
import com.cloudvault.backup.BackupType;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds local records for backups that exist in the cloud but not on this computer (new computer, reinstall, lost metadata).
 * Everything that comes from the cloud is treated as untrusted text: ids are validated before they are ever used in a file path.
 */
public class CloudImporter {
	public record Result(int imported, int alreadyKnown, int skipped) {}

	private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Pattern FOLDER_RE = Pattern.compile("^(.*) \\[([0-9a-fA-F]{8})\\]$");

	private final BackupStore store;
	private final CloudManager cloud;
	private final Function<String, String> worldIdForPrefix;

	/** @param worldIdForPrefix finds a locally known world id by its first 8 characters (or null) */
	public CloudImporter(BackupStore store, CloudManager cloud, Function<String, String> worldIdForPrefix) {
		this.store = store; this.cloud = cloud; this.worldIdForPrefix = worldIdForPrefix;
	}

	/** What is in the cloud right now, fetched once so the import menu can be browsed without more requests. */
	public record Snapshot(List<CloudProvider.RemoteFile> files, Set<String> knownRemoteIds) {}

	/** One world folder in the cloud. */
	public record CloudWorld(String folder, String worldName, String id8, int count, int notImported, String newest, long bytes) {}

	/** One backup file in the cloud, with the details read from its file name. */
	public record CloudEntry(CloudProvider.RemoteFile file, CloudFileName.Parsed parsed, boolean imported) {}

	/** Blocking (network): call from a background thread. */
	public Snapshot fetch() throws IOException {
		CloudProvider p = cloud.provider();
		if (p == null || !p.isAuthenticated()) throw new CloudException(CloudException.Kind.AUTH, "Not connected to the cloud");
		Set<String> known = new HashSet<>();
		for (BackupMetadata m : store.all()) if (m.remoteId != null && m.provider().equals(p.id())) known.add(m.remoteId);
		List<CloudProvider.RemoteFile> files = new ArrayList<>();
		for (CloudProvider.RemoteFile f : p.listAllBackups()) if (CloudFileName.parse(f.name()) != null) files.add(f);
		return new Snapshot(files, known);
	}

	private static String folderOf(CloudProvider.RemoteFile f) { return f.props().getOrDefault("cv_folder", ""); }

	/** World folders found in the cloud, newest activity first. No network. */
	public List<CloudWorld> worlds(Snapshot s) {
		Map<String, List<CloudProvider.RemoteFile>> by = new LinkedHashMap<>();
		for (var f : s.files()) by.computeIfAbsent(folderOf(f), k -> new ArrayList<>()).add(f);
		List<CloudWorld> out = new ArrayList<>();
		by.forEach((folder, fs) -> {
			String name = folder, id8 = "";
			Matcher fm = FOLDER_RE.matcher(folder);
			if (fm.matches()) { name = fm.group(1); id8 = fm.group(2); }
			if (name.isBlank()) { var pn = CloudFileName.parse(fs.get(0).name()); name = pn != null && pn.worldName() != null ? pn.worldName() : "Unknown world"; }
			long bytes = 0; int fresh = 0; String newest = "";
			for (var f : fs) {
				bytes += f.size();
				if (!s.knownRemoteIds().contains(f.id())) fresh++;
				var pn = CloudFileName.parse(f.name());
				String w = pn.date().isEmpty() ? "" : pn.date() + " " + pn.time().replace('-', ':');
				if (w.compareTo(newest) > 0) newest = w;
			}
			out.add(new CloudWorld(folder, name, id8, fs.size(), fresh, newest, bytes));
		});
		out.sort(Comparator.comparing(CloudWorld::newest).reversed().thenComparing(CloudWorld::worldName));
		return out;
	}

	/** The backups in one world folder, newest first. No network. */
	public List<CloudEntry> entries(Snapshot s, String folder) {
		List<CloudEntry> out = new ArrayList<>();
		for (var f : s.files()) if (folderOf(f).equals(folder)) out.add(new CloudEntry(f, CloudFileName.parse(f.name()), s.knownRemoteIds().contains(f.id())));
		out.sort(Comparator.comparing((CloudEntry e) -> e.parsed().date() + e.parsed().time()).reversed().thenComparing(e -> e.file().name()));
		return out;
	}

	/** Imports everything that is not known yet. */
	public Result importAll() throws IOException {
		Snapshot s = fetch();
		return importFiles(s.files().stream().filter(f -> !s.knownRemoteIds().contains(f.id())).toList());
	}

	/** Imports the chosen cloud files (blocking: one small request each on some providers). Files that are already known are skipped. */
	public Result importFiles(List<CloudProvider.RemoteFile> files) throws IOException {
		CloudProvider p = cloud.provider();
		if (p == null || !p.isAuthenticated()) throw new CloudException(CloudException.Kind.AUTH, "Not connected to the cloud");
		Set<String> knownRemote = new HashSet<>(), knownIds = new HashSet<>();
		for (BackupMetadata m : store.all()) {
			knownIds.add(m.backupId);
			if (m.remoteId != null && m.provider().equals(p.id())) knownRemote.add(m.remoteId);
		}
		int imported = 0, known = 0, skipped = 0;
		for (CloudProvider.RemoteFile f : files) {
			if (knownRemote.contains(f.id())) { known++; continue; }
			try {
				BackupMetadata m = build(p, p.details(f));
				if (m == null) { skipped++; continue; }
				if (knownIds.contains(m.backupId)) { known++; continue; }
				store.save(m); knownIds.add(m.backupId); knownRemote.add(f.id()); imported++;
			} catch (CloudException e) {
				if (e.kind == CloudException.Kind.AUTH || e.kind == CloudException.Kind.NETWORK) throw e;
				skipped++;
			} catch (RuntimeException e) { skipped++; }
		}
		return new Result(imported, known, skipped);
	}

	private BackupMetadata build(CloudProvider p, CloudProvider.RemoteFile f) {
		CloudFileName.Parsed pn = CloudFileName.parse(f.name());
		if (pn == null) return null;
		Map<String, String> pr = new HashMap<>();
		f.props().forEach((k, v) -> { if (v != null) pr.put(k.toLowerCase(Locale.ROOT), v); });

		String folder = pr.getOrDefault("cv_folder", "");
		String name = folder, id8 = null;
		Matcher fm = FOLDER_RE.matcher(folder);
		if (fm.matches()) { name = fm.group(1); id8 = fm.group(2); }
		if (name.isBlank() && pn.worldName() != null) name = pn.worldName(); // the file name carries the world name too
		if (id8 == null) id8 = pn.id8();

		String worldId = pr.getOrDefault("worldid", "");
		if (!UUID_RE.matcher(worldId).matches()) {
			worldId = id8 == null ? null : worldIdForPrefix.apply(id8);
			if (worldId == null) worldId = id8 != null ? id8 : UUID.nameUUIDFromBytes(folder.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
		}
		String bid = pr.getOrDefault("backupid", "");
		if (!UUID_RE.matcher(bid).matches()) bid = UUID.randomUUID().toString();

		BackupMetadata m = new BackupMetadata();
		m.backupId = bid; m.worldId = worldId;
		m.worldName = name.replaceAll("\\p{Cntrl}", "").trim();
		if (m.worldName.isEmpty()) m.worldName = "Imported world";
		if (m.worldName.length() > 80) m.worldName = m.worldName.substring(0, 80);
		try { m.backupType = BackupType.valueOf(pr.getOrDefault("backuptype", "").toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException e) { m.backupType = pn.type() != null ? pn.type() : BackupType.SAFETY; } // unknown type: never auto-deleted
		m.timestamp = timestamp(pr.get("timestamp"), pn);
		String mc = pr.getOrDefault("mcversion", "unknown").replaceAll("[^A-Za-z0-9._+ -]", "");
		m.minecraftVersion = mc.isEmpty() ? "unknown" : mc;
		m.cloudVaultVersion = "imported";
		m.encrypted = f.name().endsWith(".cvenc") || "1".equals(pr.get("encrypted"));
		String sha = pr.get("sha256");
		m.sha256 = sha != null && sha.matches("[0-9a-fA-F]{64}") ? sha.toLowerCase(Locale.ROOT) : null; // null = checked on first restore
		m.md5 = m.encrypted ? null : f.md5();
		m.fileSize = f.size(); m.cloudSize = f.size(); m.cloudMd5 = f.md5();
		m.localVerified = m.sha256 != null;
		if ("DELTA".equalsIgnoreCase(pr.get("kind")) || pn.delta()) m.kind = "DELTA"; // what it needs is read from the file list inside the zip when it is restored
		m.uploadStatus = UploadStatus.UPLOADED_VERIFIED;
		m.remoteId = f.id(); m.cloudProvider = p.id(); m.localFilePresent = false;
		m.zipFileName = null; // never trust a file name from the cloud as a local path
		return m;
	}

	private static long timestamp(String prop, CloudFileName.Parsed pn) {
		try { if (prop != null) { long t = Long.parseLong(prop); if (t > 0) return t; } } catch (NumberFormatException ignored) { /* fall through */ }
		long approx = pn.approxMillis();
		return approx > 0 ? approx : System.currentTimeMillis();
	}
}
