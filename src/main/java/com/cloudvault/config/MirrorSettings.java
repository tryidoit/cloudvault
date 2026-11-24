package com.cloudvault.config;

/**
 * Second destination: every backup is also copied here. type = "local" (a folder: a NAS, a USB drive, or a Dropbox / OneDrive / iCloud
 * folder), "s3" (S3-compatible storage) or "google_drive" (a second Google account). Secret keys are in the secret store.
 */
public class MirrorSettings {
	public boolean enabled = false;
	public String type = "local";
	public String localPath = "";
	public String s3Endpoint = "", s3Region = "auto", s3Bucket = "", s3AccessKey = "";
	public boolean s3PathStyle = true;

	public void sanitize() { if (type == null || !java.util.List.of("local", "s3", "google_drive").contains(type)) type = "local"; if (localPath == null) localPath = ""; }
}
