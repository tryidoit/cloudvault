package com.cloudvault.backup;

import java.util.UUID;

/** One JSON file per backup in cloudvault/metadata/. Plain fields so Gson can read/write it. */
public class BackupMetadata implements Cloneable {
	public enum UploadStatus { LOCAL_ONLY, PENDING, UPLOADING, UPLOADED_VERIFIED, FAILED }

	public String backupId = UUID.randomUUID().toString();
	public String worldName;
	public String worldId;
	public BackupType backupType;
	public long timestamp;            // epoch millis (UTC)
	public String minecraftVersion;
	public String cloudVaultVersion;
	public long fileSize;
	public String sha256;
	public String md5;
	public boolean localVerified;     // ZIP re-read and checksummed OK
	public UploadStatus uploadStatus = UploadStatus.PENDING;
	public String remoteId;           // provider file id
	public String contentFingerprint; // for duplicate detection
	public int uploadAttempts;
	public long nextAttemptAt;        // epoch millis
	public String lastError;          // short, secret-free message
	public boolean localFilePresent = true;
	public String cloudProvider;      // id of the provider holding the cloud copy; null = google_drive (backups made before S3 existed)
	public boolean pinned;            // never deleted by retention
	public String note;               // short text the player added (local only)
	public String comment;            // comment typed when the backup was made; also stored inside the zip (cloudvault-comment.txt)
	public long testedAt;             // when this backup last passed or failed a restore test (0 = never)
	public String testResult;         // "OK" or the reason it failed / was skipped
	public boolean encrypted;         // the CLOUD copy is encrypted (the local zip is plain)
	public long cloudSize;            // size of the file as stored in the cloud (differs from fileSize when encrypted)
	public String cloudMd5;           // md5 of the cloud file (encrypted copy when encrypted)
	public String kind;               // null or "FULL" = a normal complete backup; "DELTA" = only what changed since parentId (see incremental/)
	public String parentId, baseId;   // DELTA: the backup it was made against / the full backup its chain started from
	public java.util.List<String> dependsOn; // DELTA: every backup that holds a file this one needs (never modified in place)
	public int chainDepth;            // 0 for a full backup, 1.. for each incremental backup after it
	public long baseTimestamp;        // when this chain's full backup was made
	public int changedFiles, totalFiles; public long worldBytes; // sizes of the world at backup time (uncompressed)
	public String mirrorRemoteId, mirrorProvider, mirrorError; // second destination copy (null = none yet)
	public int mirrorAttempts; public long mirrorSize; public boolean mirrorEncrypted;
	public boolean missingInCloud;    // set by "Refresh cloud": was uploaded but the cloud no longer has it

	/** Independent copy (all fields are primitives, strings or enums). */
	public BackupMetadata copy() { try { return (BackupMetadata) super.clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); } }

	public boolean isDelta() { return "DELTA".equals(kind); }

	public String provider() { return cloudProvider == null ? "google_drive" : cloudProvider; }

	public String zipFileName;        // set when the backup is created; null for backups made before timestamped names

	/** See CloudFileName: backup-<date>_<time>_<zone>_<TYPE>_<world>_<id8>.zip. Legacy: backup-<uuid>.zip */
	public String fileName() { return zipFileName != null ? zipFileName : "backup-" + backupId + ".zip"; }

}
