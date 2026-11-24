package com.cloudvault.cloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Everything cloud-specific hides behind this. Implementations must be thread-safe and never log secrets. */
public interface CloudProvider {
	record RemoteFile(String id, String name, long size, String md5, Map<String, String> props) {}
	record Status(boolean connected, String account, String message) {}
	/** used/total in bytes; total = -1 when the provider has no quota (or it is unlimited). */
	record StorageInfo(long used, long total) {}

	String id();
	boolean isAuthenticated();
	/** Starts browser sign-in; blocks until done or failed. Call from a background thread. */
	void authenticate() throws IOException;
	void signOut() throws IOException;
	Status testConnection();

	RemoteFile uploadBackup(Path file, String remoteName, String worldFolder, Map<String, String> props) throws IOException;
	void downloadBackup(String remoteId, Path target) throws IOException;
	List<RemoteFile> listBackups(String worldFolder) throws IOException;
	void deleteBackup(String remoteId) throws IOException;

	/** Account storage usage, or null if the provider cannot tell. */
	default StorageInfo storageInfo() throws IOException { return null; }
	/** A web link to the CloudVault folder (for "Open cloud folder"), or null if there is none. */
	default String folderUrl() throws IOException { return null; }
	/** Fills in the custom properties of a listed file when the listing does not carry them (one extra request). */
	default RemoteFile details(RemoteFile f) throws IOException { return f; }
	/**
	 * Files outside the backup area: area "sync" (world copies and locks), "account" (settings files) or "shares" (shared backups).
	 * Each lives in its own top-level cloud folder, one sub-folder per {@code folder}. Providers that cannot do it throw.
	 */
	default RemoteFile putFile(String area, String folder, String name, Path file, Map<String, String> props) throws IOException {
		throw new CloudException(CloudException.Kind.OTHER, "This cloud provider does not support " + area);
	}
	/** @param folder only this sub-folder, or null for all; props carry cv_folder. Only names starting with {@code namePrefix}. */
	default List<RemoteFile> listFiles(String area, String folder, String namePrefix) throws IOException { return List.of(); }
	/** A link that lets anyone download the file (valid about {@code seconds} where the service allows it). */
	default String shareLink(String remoteId, int seconds) throws IOException {
		throw new CloudException(CloudException.Kind.OTHER, "This storage cannot make share links");
	}

	/** Cloud sync: uploads a world copy into the separate sync area (not the backup area). */
	default RemoteFile uploadSync(Path file, String remoteName, String syncFolder, Map<String, String> props) throws IOException { return putFile("sync", syncFolder, remoteName, file, props); }
	/** Every cloud sync file across all synced worlds (props carry cv_folder). */
	default List<RemoteFile> listSync() throws IOException { return listFiles("sync", null, "sync-"); }
	/** Every CloudVault backup in the cloud, across all worlds. */
	default List<RemoteFile> listAllBackups() throws IOException { return listBackups(null); }
}
