package com.cloudvault.backup;

import com.cloudvault.CloudVault;
import com.cloudvault.backup.BackupMetadata.UploadStatus;
import com.cloudvault.cloud.CloudProvider;
import com.cloudvault.config.BackupSettings;
import com.cloudvault.config.RetentionPolicy;

import java.util.List;

/** Per-category retention. Only ever deletes OLDER, GOOD backups, and only after a newer good one exists. */
public class RetentionManager {
	private final BackupStore store;
	public RetentionManager(BackupStore store) { this.store = store; }
	/** Called just before a backup is deleted (the core uses it to delete the second-destination copy too). */
	public volatile java.util.function.Consumer<BackupMetadata> onDeleted = m -> {};

	static boolean isGood(BackupMetadata m) {
		return m.uploadStatus == UploadStatus.UPLOADED_VERIFIED || (m.uploadStatus == UploadStatus.LOCAL_ONLY && m.localVerified);
	}

	public void apply(String worldId, BackupType.Category cat, int retention, CloudProvider provider) { apply(worldId, cat, retention, null, provider); }

	/** @param policy advanced policy; when non-null and enabled it replaces the simple "keep N previous" number. */
	public void apply(String worldId, BackupType.Category cat, int retention, RetentionPolicy policy, CloudProvider provider) {
		if (cat == BackupType.Category.SAFETY) return;
		List<BackupMetadata> list = store.forWorld(worldId).stream().filter(m -> m.backupType.category == cat && !m.pinned).toList(); // newest first; pinned backups are never touched and do not count against the limits
		List<BackupMetadata> doomed;
		if (policy != null && policy.enabled) {
			policy.sanitize();
			java.util.Set<String> keepIds = RetentionPlanner.keep(list, policy, java.time.ZonedDateTime.now(), RetentionManager::isGood);
			doomed = list.stream().filter(m -> !keepIds.contains(m.backupId)).toList();
		} else {
			if (retention == BackupSettings.KEEP_ALL) return;
			int keep = 1 + Math.max(0, retention);
			if (list.size() <= keep) return;
			if (!isGood(list.get(0))) return; // newest replacement isn't verified yet: delete nothing
			doomed = list.subList(keep, list.size());
		}
		for (BackupMetadata old : doomed) {
			if (!isGood(old)) continue; // keep pending/failed backups: they may be the only copy
			if (store.isRequired(old.backupId)) continue; // an incremental backup that is kept still needs this one; it goes when nothing needs it any more
			try {
				if (old.remoteId != null) {
					if (provider == null || !old.provider().equals(provider.id())) continue; // stored with another provider: leave it alone
					provider.deleteBackup(old.remoteId);
				}
				onDeleted.accept(old);
				store.deleteLocal(old);
			} catch (Exception e) {
				CloudVault.LOGGER.warn("Retention could not delete backup {}: {}", old.backupId, e.getMessage());
			}
		}
	}
}
