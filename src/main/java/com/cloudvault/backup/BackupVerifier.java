package com.cloudvault.backup;

import com.cloudvault.util.HashUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BackupVerifier {
	private BackupVerifier() {}

	/** Fully reads every entry (this checks each CRC) and requires a level.dat. Throws IOException if bad. */
	public static void verifyZip(Path zip) throws IOException {
		boolean hasLevel = false;
		int count = 0;
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> en = zf.entries();
			byte[] buf = new byte[64 * 1024];
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				count++;
				if (e.getName().equals("level.dat")) hasLevel = true;
				if (e.isDirectory()) continue;
				try (InputStream in = zf.getInputStream(e)) {
					while (in.read(buf) > 0) { /* CRC checked at end of stream */ }
				}
			}
		} catch (java.util.zip.ZipException e) {
			throw new IOException("Backup archive is corrupt: " + e.getMessage(), e);
		}
		if (count == 0) throw new IOException("Backup archive is empty");
		if (!hasLevel) throw new IOException("Backup archive has no level.dat (not a Minecraft world)");
	}

	public static void verifyChecksum(Path file, String expectedSha256) throws IOException {
		String actual = HashUtil.hashFile(file).sha256();
		if (!actual.equalsIgnoreCase(expectedSha256)) throw new IOException("Checksum mismatch (file damaged or changed)");
	}
}
