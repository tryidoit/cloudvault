package com.cloudvault.crypto;

import com.cloudvault.util.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Properties;

/**
 * EXPERIMENTAL. Holds the local copy of the data key (auth/encryption.properties) so automatic backups can be encrypted
 * without asking for the password. The password and recovery key are never stored; the cloud only receives encrypted files.
 */
public class EncryptionService {
	private final Path file;
	private volatile byte[] dek;
	private volatile BackupCrypto.Header header;

	public EncryptionService(Path authDir) {
		this.file = authDir.resolve("encryption.properties");
		load();
	}

	public boolean isSetUp() { return dek != null && header != null; }

	/** Creates a new key. Returns the recovery key text, which must be shown to the user exactly once. */
	public synchronized String setUp(char[] password) throws IOException {
		if (password == null || password.length < 8) throw new IOException("Password must be at least 8 characters");
		try {
			byte[] newDek = BackupCrypto.randomBytes(32), recovery = BackupCrypto.randomBytes(32);
			BackupCrypto.Header h = BackupCrypto.createHeader(newDek, password, recovery, BackupCrypto.DEFAULT_ITERATIONS);
			store(newDek, h);
			return BackupCrypto.formatRecoveryKey(recovery);
		} catch (GeneralSecurityException e) { throw new IOException("Could not create encryption key: " + e.getMessage(), e); }
		finally { java.util.Arrays.fill(password, '\0'); } // do not keep the password in memory longer than needed
	}

	/** For a new computer: take the key header from an encrypted backup and unlock it with the password OR the recovery key. */
	public synchronized void unlockFrom(Path encryptedBackup, String passwordOrRecoveryKey) throws IOException {
		BackupCrypto.Header h = BackupCrypto.readHeader(encryptedBackup);
		byte[] rec = BackupCrypto.parseRecoveryKey(passwordOrRecoveryKey);
		byte[] key = rec != null ? BackupCrypto.unwrapWithRecovery(h, rec) : BackupCrypto.unwrapWithPassword(h, passwordOrRecoveryKey.toCharArray());
		store(key, h);
	}

	/** Forget the local key (backups already in the cloud stay encrypted; you need password or recovery key to use them again). */
	public synchronized void forget() throws IOException { dek = null; header = null; Files.deleteIfExists(file); }

	public void encrypt(Path in, Path out) throws IOException {
		byte[] k = dek; BackupCrypto.Header h = header;
		if (k == null || h == null) throw new IOException("Encryption is not set up");
		BackupCrypto.encryptFile(in, out, k, h);
	}

	public void decrypt(Path in, Path out) throws IOException {
		byte[] k = dek;
		if (k == null) throw new IOException("Encrypted backup: unlock encryption first (password or recovery key)");
		BackupCrypto.decryptFile(in, out, k);
	}

	private void store(byte[] newDek, BackupCrypto.Header h) throws IOException {
		Properties p = new Properties();
		Base64.Encoder e = Base64.getEncoder();
		p.setProperty("dek", e.encodeToString(newDek));
		p.setProperty("iterations", Integer.toString(h.iterations()));
		p.setProperty("salt", e.encodeToString(h.salt()));
		p.setProperty("wrapPassword", e.encodeToString(h.wrapPassword()));
		p.setProperty("wrapRecovery", e.encodeToString(h.wrapRecovery()));
		java.io.StringWriter sw = new java.io.StringWriter();
		p.store(sw, "CloudVault encryption key (keep private; do not share this file)");
		FileUtil.writeSecret(file, sw.toString());
		this.dek = newDek; this.header = h;
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (InputStream in = Files.newInputStream(file)) {
			Properties p = new Properties(); p.load(in);
			Base64.Decoder d = Base64.getDecoder();
			this.dek = d.decode(p.getProperty("dek"));
			this.header = new BackupCrypto.Header(Integer.parseInt(p.getProperty("iterations")), d.decode(p.getProperty("salt")),
				d.decode(p.getProperty("wrapPassword")), d.decode(p.getProperty("wrapRecovery")));
		} catch (Exception e) { this.dek = null; this.header = null; }
	}
}
