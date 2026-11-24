package com.cloudvault.backup;

import com.cloudvault.util.FileUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Local layout: backups/<worldId>/<file>.zip, metadata/<id>.json, temp/, auth/. Thread-safe. */
public class BackupStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public final Path root, backups, metadata, temp, auth, manifests;

	public BackupStore(Path root) throws IOException {
		this.root = root;
		this.backups = root.resolve("backups");
		this.metadata = root.resolve("metadata");
		this.temp = root.resolve("temp");
		this.auth = root.resolve("auth");
		this.manifests = root.resolve("manifests");
		for (Path p : new Path[]{backups, metadata, temp, auth, manifests}) Files.createDirectories(p);
	}

	public Path zipPath(BackupMetadata m) { return backups.resolve(m.worldId).resolve(m.fileName()); }

	// The metadata folder is read once; after that the index is kept up to date by save/delete. Callers always get COPIES, so a
	// change only counts once save() is called (same behaviour as when every call re-read the files, but without the disk work).
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
	private static final Pattern SAFE_FILE = Pattern.compile("[^/\\\\:*?\"<>|\\p{Cntrl}.][^/\\\\:*?\"<>|\\p{Cntrl}]{0,199}");

	/** Everything in a metadata file ends up in file paths, so a hand-edited or tampered record must never be trusted. */
	static boolean valid(BackupMetadata b) {
		return b != null && b.backupId != null && b.worldId != null && b.backupType != null
			&& SAFE_ID.matcher(b.backupId).matches() && SAFE_ID.matcher(b.worldId).matches()
			&& (b.zipFileName == null || SAFE_FILE.matcher(b.zipFileName).matches());
	}

	public record Stats(int uploaded, int pending, int failed, long newestTimestamp) {}

	/** Counts for the status screen without copying every record. */
	public synchronized Stats stats() {
		int up = 0, pe = 0, fa = 0; long newest = 0;
		for (BackupMetadata m : index().values()) {
			switch (m.uploadStatus) { case UPLOADED_VERIFIED -> up++; case FAILED -> fa++; case PENDING, UPLOADING -> pe++; default -> {} }
			if (m.timestamp > newest) newest = m.timestamp;
		}
		return new Stats(up, pe, fa, newest);
	}

	private Map<String, BackupMetadata> index;

	private Map<String, BackupMetadata> index() {
		if (index == null) {
			Map<String, BackupMetadata> m = new HashMap<>();
			try (Stream<Path> s = Files.list(metadata)) {
				for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
					try {
						BackupMetadata b = GSON.fromJson(Files.readString(p), BackupMetadata.class);
						if (valid(b)) m.put(b.backupId, b);
					} catch (Exception ignored) { /* skip unreadable metadata file, never delete it */ }
				}
			} catch (IOException ignored) {}
			index = m;
		}
		return index;
	}

	public synchronized void save(BackupMetadata m) throws IOException {
		if (!valid(m)) throw new IOException("Invalid backup record (refusing to save)");
		FileUtil.writeAtomic(metadata.resolve(m.backupId + ".json"), GSON.toJson(m));
		index().put(m.backupId, m.copy());
	}

	/** Atomic read-modify-write of one record (so a screen holding an old copy cannot overwrite newer upload progress). */
	public synchronized boolean update(String backupId, java.util.function.Consumer<BackupMetadata> change) throws IOException {
		BackupMetadata cur = index().get(backupId);
		if (cur == null) return false;
		BackupMetadata copy = cur.copy();
		change.accept(copy);
		save(copy);
		return true;
	}

	/** Newest first. */
	public synchronized List<BackupMetadata> all() {
		List<BackupMetadata> out = new ArrayList<>(index().size());
		for (BackupMetadata m : index().values()) out.add(m.copy());
		out.sort(Comparator.comparingLong((BackupMetadata m) -> m.timestamp).reversed());
		return out;
	}

	public synchronized List<BackupMetadata> forWorld(String worldId) {
		List<BackupMetadata> out = new ArrayList<>();
		for (BackupMetadata m : index().values()) if (m.worldId.equals(worldId)) out.add(m.copy());
		out.sort(Comparator.comparingLong((BackupMetadata m) -> m.timestamp).reversed());
		return out;
	}

	public synchronized Optional<BackupMetadata> find(String backupId) {
		BackupMetadata m = index().get(backupId);
		return m == null ? Optional.empty() : Optional.of(m.copy());
	}

	// ---- incremental backups: which backups need which ----

	/** Backups that list {@code backupId} in dependsOn (they cannot be restored without it). */
	public synchronized List<BackupMetadata> dependentsOf(String backupId) {
		List<BackupMetadata> out = new ArrayList<>();
		for (BackupMetadata m : index().values()) if (m.dependsOn != null && m.dependsOn.contains(backupId) && !m.backupId.equals(backupId)) out.add(m.copy());
		return out;
	}

	public synchronized boolean isRequired(String backupId) {
		for (BackupMetadata m : index().values()) if (m.dependsOn != null && m.dependsOn.contains(backupId) && !m.backupId.equals(backupId)) return true;
		return false;
	}

	/** The backups and everything they depend on, recursively. */
	public synchronized java.util.Set<String> closure(Collection<String> ids) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(ids);
		while (!todo.isEmpty()) {
			String id = todo.poll();
			if (!out.add(id)) continue;
			BackupMetadata m = index().get(id);
			if (m != null && m.dependsOn != null) todo.addAll(m.dependsOn);
		}
		return out;
	}

	public void saveManifest(String backupId, String json) throws IOException {
		Path p = manifests.resolve(backupId + ".json.gz");
		Path part = manifests.resolve(backupId + ".json.gz.part");
		try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(part))) { out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
		Files.move(part, p, StandardCopyOption.REPLACE_EXISTING);
	}

	/** The file list saved with a backup, or null when there is none (backups made before incremental backups existed). */
	public String loadManifest(String backupId) {
		if (!SAFE_ID.matcher(backupId).matches()) return null;
		Path p = manifests.resolve(backupId + ".json.gz");
		if (!Files.exists(p)) return null;
		try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(p))) { return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
		catch (IOException e) { return null; }
	}

	/** Removes only metadata + local zip of one backup. */
	public synchronized void deleteLocal(BackupMetadata m) {
		try { Files.deleteIfExists(zipPath(m)); } catch (IOException ignored) {}
		try { Files.deleteIfExists(metadata.resolve(m.backupId + ".json")); } catch (IOException ignored) {}
		try { Files.deleteIfExists(manifests.resolve(m.backupId + ".json.gz")); } catch (IOException ignored) {}
		index().remove(m.backupId);
	}

	/** Called at startup: leftover temp dirs from a crashed run are safe to remove (they never hold the only copy). */
	public void cleanTemp() {
		try (Stream<Path> s = Files.list(temp)) {
			s.forEach(FileUtil::deleteQuietly);
		} catch (IOException ignored) {}
	}
}
