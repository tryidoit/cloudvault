package com.cloudvault.backup;

import com.cloudvault.backup.BackupMetadata.UploadStatus;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Filter and sort options for the backup browser (pure logic). */
public final class BackupFilter {
	private BackupFilter() {}

	public enum Filter {
		ALL("All", m -> true),
		MANUAL("Manual", m -> m.backupType == BackupType.MANUAL),
		PERIODIC("Periodic", m -> m.backupType == BackupType.PERIODIC),
		GAMEPLAY("Gameplay", m -> m.backupType == BackupType.GAMEPLAY),
		JOIN("Join", m -> m.backupType == BackupType.SESSION_JOIN),
		LEAVE("Leave", m -> m.backupType == BackupType.SESSION_LEAVE),
		PENDING("Pending", m -> m.uploadStatus == UploadStatus.PENDING || m.uploadStatus == UploadStatus.UPLOADING),
		PINNED("Pinned", m -> m.pinned),
		FAILED("Failed", m -> m.uploadStatus == UploadStatus.FAILED),
		SAFETY("Safety/Reset", m -> m.backupType.category == BackupType.Category.SAFETY);

		public final String label; final Predicate<BackupMetadata> test;
		Filter(String label, Predicate<BackupMetadata> test) { this.label = label; this.test = test; }
		public Filter next() { return values()[(ordinal() + 1) % values().length]; }
	}

	public enum Sort {
		NEWEST("Newest", Comparator.comparingLong((BackupMetadata m) -> m.timestamp).reversed()),
		OLDEST("Oldest", Comparator.comparingLong((BackupMetadata m) -> m.timestamp)),
		SIZE("Size", Comparator.comparingLong((BackupMetadata m) -> m.fileSize).reversed().thenComparing(Comparator.comparingLong((BackupMetadata m) -> m.timestamp).reversed())),
		TYPE("Type", Comparator.comparing((BackupMetadata m) -> m.backupType.name()).thenComparing(Comparator.comparingLong((BackupMetadata m) -> m.timestamp).reversed()));

		public final String label; final Comparator<BackupMetadata> cmp;
		Sort(String label, Comparator<BackupMetadata> cmp) { this.label = label; this.cmp = cmp; }
		public Sort next() { return values()[(ordinal() + 1) % values().length]; }
	}

	public static List<BackupMetadata> apply(List<BackupMetadata> in, Filter f, Sort s) {
		return in.stream().filter(f.test).sorted(s.cmp).toList();
	}
}
