package com.cloudvault.compare;

import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.cloud.CloudManager;
import com.cloudvault.incremental.Manifest;
import com.cloudvault.restore.LevelInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Compares two backups of the same world: which files were added, removed or changed, grouped in plain words, plus basic facts from level.dat. */
public final class BackupComparer {
	private BackupComparer() {}

	public record Change(String path, long sizeA, long sizeB) {}
	public record Group(String name, int added, int removed, int changed, long bytesA, long bytesB) {}
	public record Result(List<Change> added, List<Change> removed, List<Change> changed, int unchanged, long bytesA, long bytesB, List<Group> groups, List<String[]> info) {}

	private record F(long size, long crc) {}

	/** Human group of a file (works for both the old region/ layout and the newer dimensions/ layout). */
	public static String group(String p) {
		String l = p.toLowerCase(Locale.ROOT);
		if (l.equals("level.dat") || l.equals("level.dat_old")) return "World settings";
		if (l.startsWith("playerdata/") || l.startsWith("advancements/") || l.startsWith("stats/") || l.startsWith("players/")) return "Player data";
		if (l.startsWith("datapacks/")) return "Data packs";
		boolean nether = l.startsWith("dim-1/") || l.contains("the_nether"), end = l.startsWith("dim1/") || l.contains("the_end");
		String where = nether ? "Nether" : end ? "End" : "Overworld";
		if (l.contains("/region/") || l.startsWith("region/")) return where + " terrain";
		if (l.contains("/entities/") || l.startsWith("entities/") || l.contains("/poi/") || l.startsWith("poi/")) return where + " entities";
		return "Other files";
	}

	private static Map<String, F> index(BackupStore store, CloudManager cloud, BackupMetadata m) throws IOException {
		Map<String, F> out = new TreeMap<>();
		String mj = store.loadManifest(m.backupId);
		Manifest man = mj == null ? null : Manifest.fromJson(mj);
		if (man != null) { man.files.forEach((k, v) -> out.put(k, new F(v.size, v.crc))); return out; }
		Path zip = cloud.obtainVerifiedZip(m); // a backup made before file lists existed: read the zip's own directory
		try (ZipFile z = new ZipFile(zip.toFile())) {
			for (var en = z.entries(); en.hasMoreElements(); ) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory() || e.getName().equals(Manifest.ZIP_ENTRY) || e.getName().equals("cloudvault-comment.txt")) continue;
				out.put(e.getName(), new F(e.getSize(), e.getCrc()));
			}
		}
		return out;
	}

	private static List<String[]> info(BackupStore store, CloudManager cloud, BackupMetadata m, boolean allowDownload) {
		List<String[]> rows = new ArrayList<>();
		var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
		rows.add(new String[]{"Made", fmt.format(Instant.ofEpochMilli(m.timestamp))});
		rows.add(new String[]{"Type", m.backupType.label() + (m.isDelta() ? " (incremental)" : "")});
		if (m.comment != null && !m.comment.isBlank()) rows.add(new String[]{"Comment", m.comment.replace('\n', ' ')});
		Path zip = store.zipPath(m);
		try {
			if (!Files.exists(zip) && allowDownload) zip = cloud.obtainOwnZip(m);
			if (Files.exists(zip)) {
				Path tmp = Files.createTempFile(store.temp, "lvl", ".dat");
				try (ZipFile z = new ZipFile(zip.toFile())) {
					ZipEntry e = z.getEntry("level.dat");
					if (e != null) {
						try (var in = z.getInputStream(e)) { Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
						Map<String, Object> d = LevelInfo.readData(tmp);
						if (d.get("LevelName") != null) rows.add(new String[]{"World name", String.valueOf(d.get("LevelName"))});
						if (d.get("Time") instanceof Number t) rows.add(new String[]{"Playtime in world", String.format(Locale.ROOT, "%.1f days", t.longValue() / 24000.0)});
						if (d.get("LastPlayed") instanceof Number t && t.longValue() > 0) rows.add(new String[]{"Last played", fmt.format(Instant.ofEpochMilli(t.longValue()))});
						if (d.get("GameType") instanceof Number g) rows.add(new String[]{"Game mode", switch (g.intValue()) { case 0 -> "Survival"; case 1 -> "Creative"; case 2 -> "Adventure"; case 3 -> "Spectator"; default -> "?"; }});
					}
				} finally { Files.deleteIfExists(tmp); }
			}
		} catch (Exception ignored) { /* the file lists are what matters */ }
		return rows;
	}

	/** @param allowDownload read level.dat from a backup that is only in the cloud (downloads it) */
	public static Result compare(BackupStore store, CloudManager cloud, BackupMetadata a, BackupMetadata b, boolean allowDownload) throws IOException {
		Map<String, F> ia = index(store, cloud, a), ib = index(store, cloud, b);
		List<Change> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
		int same = 0; long bytesA = 0, bytesB = 0;
		for (F f : ia.values()) bytesA += f.size; for (F f : ib.values()) bytesB += f.size;
		for (var e : ib.entrySet()) {
			F x = ia.get(e.getKey());
			if (x == null) added.add(new Change(e.getKey(), 0, e.getValue().size));
			else if (x.size != e.getValue().size || x.crc != e.getValue().crc) changed.add(new Change(e.getKey(), x.size, e.getValue().size));
			else same++;
		}
		for (var e : ia.entrySet()) if (!ib.containsKey(e.getKey())) removed.add(new Change(e.getKey(), e.getValue().size, 0));

		Map<String, long[]> g = new TreeMap<>(); // added, removed, changed, bytesA, bytesB
		for (var e : ia.entrySet()) { long[] r = g.computeIfAbsent(group(e.getKey()), k -> new long[5]); r[3] += e.getValue().size; }
		for (var e : ib.entrySet()) { long[] r = g.computeIfAbsent(group(e.getKey()), k -> new long[5]); r[4] += e.getValue().size; }
		for (Change c : added) g.get(group(c.path))[0]++;
		for (Change c : removed) g.get(group(c.path))[1]++;
		for (Change c : changed) g.get(group(c.path))[2]++;
		List<Group> groups = new ArrayList<>();
		g.forEach((k, r) -> { if (r[0] + r[1] + r[2] > 0) groups.add(new Group(k, (int) r[0], (int) r[1], (int) r[2], r[3], r[4])); });
		groups.sort(Comparator.comparingInt((Group x) -> x.added() + x.removed() + x.changed()).reversed());

		List<String[]> rows = new ArrayList<>();
		List<String[]> ra = info(store, cloud, a, allowDownload), rb = info(store, cloud, b, allowDownload);
		Map<String, String> bm = new HashMap<>(); for (String[] r : rb) bm.put(r[0], r[1]);
		for (String[] r : ra) rows.add(new String[]{r[0], r[1], bm.getOrDefault(r[0], "-")});
		for (String[] r : rb) if (ra.stream().noneMatch(x -> x[0].equals(r[0]))) rows.add(new String[]{r[0], "-", r[1]});
		return new Result(added, removed, changed, same, bytesA, bytesB, groups, rows);
	}
}
