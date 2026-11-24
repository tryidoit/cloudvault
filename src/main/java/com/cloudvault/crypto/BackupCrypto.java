package com.cloudvault.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * CVENC1 file format: header | chunks. Everything is AES-256-GCM.
 * A random 256-bit data key (DEK) encrypts the chunks. The header carries the DEK wrapped twice: once with a key derived
 * from the user's password (PBKDF2-HMAC-SHA256) and once with the random recovery key. So every encrypted backup is
 * self-contained: password OR recovery key opens it, and the cloud only ever sees wrapped keys.
 * Chunks (1 MiB) are authenticated with their index and a "last chunk" flag, so reordering or truncation is detected.
 */
public final class BackupCrypto {
	private BackupCrypto() {}

	static final byte[] MAGIC = "CVENC1\0\0".getBytes(StandardCharsets.US_ASCII);
	static final int CHUNK = 1 << 20;
	public static final int DEFAULT_ITERATIONS = 600_000;
	private static final int WRAP_LEN = 12 + 32 + 16;
	private static final int HEADER_LEN = 8 + 4 + 16 + WRAP_LEN + WRAP_LEN + 8;
	private static final SecureRandom RNG = new SecureRandom();

	/** The per-installation key material that goes into every file header. */
	public record Header(int iterations, byte[] salt, byte[] wrapPassword, byte[] wrapRecovery) {}

	public static class WrongKeyException extends IOException {
		public WrongKeyException(String m) { super(m); }
	}

	public static byte[] randomBytes(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

	// ---- key creation / unwrap ----

	public static Header createHeader(byte[] dek, char[] password, byte[] recoveryKey, int iterations) throws GeneralSecurityException {
		byte[] salt = randomBytes(16);
		byte[] kek = pbkdf2(password, salt, iterations);
		return new Header(iterations, salt, wrap(kek, dek), wrap(recoveryKey, dek));
	}

	/** Returns the DEK or throws WrongKeyException. */
	public static byte[] unwrapWithPassword(Header h, char[] password) throws IOException {
		try { return unwrap(pbkdf2(password, h.salt, h.iterations), h.wrapPassword); }
		catch (GeneralSecurityException e) { throw new WrongKeyException("Wrong password"); }
	}

	public static byte[] unwrapWithRecovery(Header h, byte[] recoveryKey) throws IOException {
		try { return unwrap(recoveryKey, h.wrapRecovery); }
		catch (GeneralSecurityException e) { throw new WrongKeyException("Wrong recovery key"); }
	}

	private static byte[] pbkdf2(char[] pw, byte[] salt, int iters) throws GeneralSecurityException {
		PBEKeySpec spec = new PBEKeySpec(pw, salt, iters, 256);
		try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
		finally { spec.clearPassword(); }
	}

	private static byte[] wrap(byte[] kek, byte[] dek) throws GeneralSecurityException {
		byte[] nonce = randomBytes(12);
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, nonce));
		c.updateAAD("CVWRAP1".getBytes(StandardCharsets.US_ASCII));
		byte[] ct = c.doFinal(dek);
		return ByteBuffer.allocate(WRAP_LEN).put(nonce).put(ct).array();
	}

	private static byte[] unwrap(byte[] kek, byte[] w) throws GeneralSecurityException {
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, w, 0, 12));
		c.updateAAD("CVWRAP1".getBytes(StandardCharsets.US_ASCII));
		return c.doFinal(w, 12, w.length - 12);
	}

	// ---- recovery key text form: 52 base32 chars in groups of 4 ----

	private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	public static String formatRecoveryKey(byte[] key) {
		StringBuilder raw = new StringBuilder();
		int buf = 0, bits = 0;
		for (byte b : key) {
			buf = (buf << 8) | (b & 0xFF); bits += 8;
			while (bits >= 5) { raw.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
		}
		if (bits > 0) raw.append(B32.charAt((buf << (5 - bits)) & 31));
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) { if (i > 0 && i % 4 == 0) out.append('-'); out.append(raw.charAt(i)); }
		return out.toString();
	}

	/** Accepts any case, with or without dashes/spaces. Returns null if it is not a valid recovery key. */
	public static byte[] parseRecoveryKey(String text) {
		if (text == null) return null;
		String s = text.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
		if (s.length() != 52) return null;
		byte[] out = new byte[32];
		int buf = 0, bits = 0, n = 0;
		for (char ch : s.toCharArray()) {
			int v = B32.indexOf(ch);
			if (v < 0) return null;
			buf = (buf << 5) | v; bits += 5;
			if (bits >= 8 && n < 32) { out[n++] = (byte) ((buf >> (bits - 8)) & 0xFF); bits -= 8; }
		}
		return n == 32 ? out : null;
	}

	// ---- file encryption ----

	private static byte[] encodeHeader(Header h, byte[] noncePrefix) {
		return ByteBuffer.allocate(HEADER_LEN).put(MAGIC).putInt(h.iterations).put(h.salt).put(h.wrapPassword).put(h.wrapRecovery).put(noncePrefix).array();
	}

	public static Header readHeader(Path file) throws IOException {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) { return parseHeader(in.readNBytes(HEADER_LEN)); }
	}

	public static boolean looksEncrypted(Path file) {
		try (InputStream in = Files.newInputStream(file)) { return Arrays.equals(in.readNBytes(8), MAGIC); }
		catch (IOException e) { return false; }
	}

	private static Header parseHeader(byte[] raw) throws IOException {
		if (raw.length != HEADER_LEN || !Arrays.equals(Arrays.copyOf(raw, 8), MAGIC)) throw new IOException("Not a CloudVault encrypted backup");
		ByteBuffer b = ByteBuffer.wrap(raw); b.position(8);
		int iters = b.getInt();
		if (iters < 1000 || iters > 10_000_000) throw new IOException("Encrypted backup header is damaged");
		byte[] salt = new byte[16], wp = new byte[WRAP_LEN], wr = new byte[WRAP_LEN];
		b.get(salt); b.get(wp); b.get(wr);
		return new Header(iters, salt, wp, wr);
	}

	public static void encryptFile(Path in, Path out, byte[] dek, Header header) throws IOException {
		byte[] prefix = randomBytes(8);
		byte[] hdr = encodeHeader(header, prefix);
		byte[] hdrHash = sha256(hdr);
		try (InputStream is = new BufferedInputStream(Files.newInputStream(in), 1 << 16);
		     OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
			os.write(hdr);
			long idx = 0;
			while (true) { // the last chunk is the first one shorter than CHUNK (possibly empty), so decryption can tell it without lookahead
				byte[] cur = is.readNBytes(CHUNK);
				boolean last = cur.length < CHUNK;
				byte[] ct = crypt(Cipher.ENCRYPT_MODE, dek, prefix, hdrHash, idx, last, cur);
				os.write(ByteBuffer.allocate(4).putInt(ct.length).array());
				os.write(ct);
				if (last) break;
				idx++;
			}
		} catch (GeneralSecurityException e) { throw new IOException("Encryption failed: " + e.getMessage(), e); }
	}

	/** Decrypts and authenticates the whole file. Throws WrongKeyException (wrong key) or IOException (damaged/truncated). */
	public static void decryptFile(Path in, Path out, byte[] dek) throws IOException {
		try (InputStream is = new BufferedInputStream(Files.newInputStream(in), 1 << 16)) {
			byte[] hdr = is.readNBytes(HEADER_LEN);
			parseHeader(hdr);
			byte[] prefix = Arrays.copyOfRange(hdr, HEADER_LEN - 8, HEADER_LEN);
			byte[] hdrHash = sha256(hdr);
			try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
				long idx = 0;
				while (true) {
					byte[] lenB = is.readNBytes(4);
					if (lenB.length != 4) throw new IOException("Encrypted backup is truncated");
					int len = ByteBuffer.wrap(lenB).getInt();
					if (len < 16 || len > CHUNK + 16) throw new IOException("Encrypted backup is damaged");
					byte[] ct = is.readNBytes(len);
					if (ct.length != len) throw new IOException("Encrypted backup is truncated");
					boolean last = ct.length - 16 < CHUNK; // a full chunk is never the last one (see encryptFile)
					byte[] pt;
					try { pt = crypt(Cipher.DECRYPT_MODE, dek, prefix, hdrHash, idx, last, ct); }
					catch (javax.crypto.AEADBadTagException e) {
						throw idx == 0 ? new WrongKeyException("Wrong key, or the backup is damaged") : new IOException("Encrypted backup is damaged");
					}
					os.write(pt);
					if (last) { if (is.read() != -1) throw new IOException("Encrypted backup has extra data"); break; }
					idx++;
				}
			}
		} catch (GeneralSecurityException e) { throw new IOException("Decryption failed: " + e.getMessage(), e); }
	}

	private static byte[] crypt(int mode, byte[] dek, byte[] prefix, byte[] hdrHash, long idx, boolean last, byte[] data) throws GeneralSecurityException {
		byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt((int) idx).array();
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(mode, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
		c.updateAAD(ByteBuffer.allocate(32 + 8 + 1).put(hdrHash).putLong(idx).put((byte) (last ? 1 : 0)).array());
		return c.doFinal(data);
	}

	private static byte[] sha256(byte[] b) {
		try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
	}
}
