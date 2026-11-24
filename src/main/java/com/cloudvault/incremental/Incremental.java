package com.cloudvault.incremental;

import com.cloudvault.backup.BackupManager;
import com.cloudvault.backup.BackupMetadata;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.backup.BackupStore;
import com.cloudvault.backup.BackupType;
import com.cloudvault.config.BackupSettings;

import java.nio.file.Files;
import java.util.*;

/**
 * Decides, for one new backup, whether it may be stored as "only what changed since the previous backup" - and refuses whenever
 * that could leave the player worse off than a normal full backup:
 *  - no usable previous backup, or anything it needs is not available            -> full
 *  - the chain is long (never longer than the number of kept backups allows), or its full backup is older than the set days -> full
 *  - too much of the world changed (the delta would be nearly a full backup)     -> full
 *  - a chain would cost more than N/(N+L) of the same number of full backups (N kept backups, chains of at most L) -> full.
 *    Retention keeps the newest N plus whatever those need; with chains of at most L, that touches at most N+L backups, so the
 *    total can never exceed what N full backups would take.
 *  - advanced retention rules are on (they could delete a backup the chain needs) -> full
 * Safety backups (before restore / reset / update) are always full. Every decision is based on file CONTENT hashes, never on dates.
 */
public final class Incremental {
	private Incremental() {}

	/** @param parent null = make a normal full backup; {@code manifest} is then still recorded (when not null) so the NEXT backup can be incremental */
	public record Decision(Manifest manifest, BackupMetadata parent, Set<String> changed, long changedBytes, long estFullBytes, String why) {
		public boolean isDelta() { return parent != null; }
	}

	/** Names that are not part of the world (added by CloudVault itself). */
	public static final Set<String> SKIP = Set.of(BackupManager.COMMENT_FILE, Manifest.ZIP_ENTRY);

	private static Decision full(Manifest m, String why) { return new Decision(m, null, Set.of(), 0, 0, why); }

	private static boolean available(BackupStore store, BackupMetadata m) {
		boolean local = m.localFilePresent && Files.exists(store.zipPath(m));
		boolean cloud = m.uploadStatus == UploadStatus.UPLOADED_VERIFIED && m.remoteId != null && !m.missingInCloud;
		return (local && (m.localVerified || m.sha256 != null)) || cloud;
	}

	private static long sizeOf(BackupMetadata m) { return m.cloudSize > 0 ? m.cloudSize : m.fileSize; }

	/**
	 * @param scanned manifest of the copied world (every file attributed to {@code newId})
	 * @param parentCandidates this world's backups, newest first
	 */
	public static Decision decide(BackupStore store, BackupSettings s, BackupType type, List<BackupMetadata> parentCandidates, Manifest scanned, String newId, long now) {
		if (scanned == null) return full(null, "no file list");
		if (!s.incremental.enabled || type.category == BackupType.Category.SAFETY) return full(scanned, "full backups only here");
		if (s.advanced.enabled) return full(scanned, "advanced retention is on");

		BackupMetadata parent = null;
		for (BackupMetadata m : parentCandidates) if (m.backupType.category == type.category) { parent = m; break; } // newest of the same kind only
		if (parent == null) return full(scanned, "first backup of this kind");
		if (!parent.localVerified && parent.uploadStatus != UploadStatus.UPLOADED_VERIFIED) return full(scanned, "previous backup is not verified");

		Manifest pm = Manifest.fromJson(Objects.requireNonNullElse(store.loadManifest(parent.backupId), ""));
		if (pm == null || !parent.backupId.equals(pm.backupId)) return full(scanned, "previous backup has no file list");

		// everything the chain needs must still be reachable
		Set<String> needed = new LinkedHashSet<>(); needed.add(parent.backupId);
		if (parent.dependsOn != null) needed.addAll(parent.dependsOn);
		for (String id : needed) {
			BackupMetadata d = store.find(id).orElse(null);
			if (d == null || !available(store, d)) return full(scanned, "a backup in the chain is not available");
		}
		for (var e : pm.files.entrySet()) if (!needed.contains(e.getValue().src)) return full(scanned, "file list points outside the chain");

		// Space rule (see class comment).
		int retention = BackupManager.retentionFor(s, type.category);
		boolean keepAll = retention == BackupSettings.KEEP_ALL;
		int points = keepAll ? Integer.MAX_VALUE : 1 + retention;
		int maxChain = keepAll ? s.incremental.maxChain : Math.min(s.incremental.maxChain, points - 1);
		if (maxChain < 1) return full(scanned, "too few backups are kept for a chain to pay off");
		if (parent.chainDepth + 1 > maxChain) return full(scanned, "chain is long enough");
		long baseTs = parent.baseTimestamp > 0 ? parent.baseTimestamp : parent.timestamp;
		if (now - baseTs > s.incremental.fullEveryDays * 86_400_000L) return full(scanned, "time for a fresh full backup");

		// what changed (by content)
		Set<String> changed = new TreeSet<>();
		long changedBytes = 0;
		for (var e : scanned.files.entrySet()) {
			Manifest.Entry old = pm.files.get(e.getKey());
			if (old == null || !old.sha.equals(e.getValue().sha) || old.size != e.getValue().size) { changed.add(e.getKey()); changedBytes += e.getValue().size; }
		}
		if (scanned.files.containsKey("level.dat")) { // always inside, so every zip of ours still looks like a world to the checks
			if (changed.add("level.dat")) changedBytes += scanned.files.get("level.dat").size;
		}
		long total = Math.max(1, scanned.totalBytes);
		if (changedBytes * 100 > total * s.incremental.maxChangedPercent) return full(scanned, "too much changed");

		// estimate what a FULL backup of the world would weigh now, from this chain's own full backup
		String baseId = parent.isDelta() ? parent.baseId : parent.backupId;
		BackupMetadata base = baseId == null ? null : store.find(baseId).orElse(null);
		if (base == null) return full(scanned, "the chain's full backup is not available");
		double ratio = base.worldBytes > 0 && base.fileSize > 0 ? (double) base.fileSize / base.worldBytes : 0.6;
		long estFull = (long) (total * ratio), estDelta = (long) (changedBytes * ratio);

		long chainBytes = estDelta; int members = 1;
		for (BackupMetadata m : parentCandidates) if (m.backupId.equals(baseId) || baseId.equals(m.baseId)) { chainBytes += sizeOf(m); members++; }
		double rho = keepAll ? 0.85 : (double) points / (points + maxChain);
		if (chainBytes > rho * members * estFull) return full(scanned, "this chain would cost more than full backups would");

		// the new file list: changed files come from this backup, the rest from where the parent had them
		Manifest nm = new Manifest();
		nm.backupId = newId; nm.parentId = parent.backupId; nm.createdAt = scanned.createdAt; nm.totalBytes = scanned.totalBytes;
		for (var e : scanned.files.entrySet()) {
			Manifest.Entry x = e.getValue();
			nm.files.put(e.getKey(), new Manifest.Entry(x.size, x.crc, x.sha, changed.contains(e.getKey()) ? newId : pm.files.get(e.getKey()).src)); // copies: the scan stays a valid FULL list
		}
		return new Decision(nm, parent, changed, changedBytes, estFull, "incremental");
	}

	/** The backups (other than itself) that hold content this manifest uses. */
	public static List<String> dependsOn(Manifest m, String selfId) {
		TreeSet<String> out = new TreeSet<>();
		for (Manifest.Entry e : m.files.values()) if (!e.src.equals(selfId)) out.add(e.src);
		return new ArrayList<>(out);
	}
}
