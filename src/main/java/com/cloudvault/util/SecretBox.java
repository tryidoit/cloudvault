package com.cloudvault.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Password-based encryption for small secrets (PBKDF2-SHA256 + AES-256-GCM). Format: "CVS1" | salt(16) | iv(12) | ciphertext+tag. */
public final class SecretBox {
	private SecretBox() {}
	private static final byte[] MAGIC = {'C', 'V', 'S', '1'};
	private static final int ITER = 200_000;
	private static final SecureRandom RNG = new SecureRandom();

	private static SecretKeySpec key(char[] password, byte[] salt) throws GeneralSecurityException {
		byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, ITER, 256)).getEncoded();
		return new SecretKeySpec(k, "AES");
	}

	public static byte[] seal(char[] password, byte[] plain) throws GeneralSecurityException {
		byte[] salt = new byte[16], iv = new byte[12];
		RNG.nextBytes(salt); RNG.nextBytes(iv);
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
		byte[] ct = c.doFinal(plain);
		byte[] out = new byte[4 + 16 + 12 + ct.length];
		System.arraycopy(MAGIC, 0, out, 0, 4); System.arraycopy(salt, 0, out, 4, 16); System.arraycopy(iv, 0, out, 20, 12); System.arraycopy(ct, 0, out, 32, ct.length);
		return out;
	}

	/** @throws GeneralSecurityException wrong password or damaged data (GCM detects both) */
	public static byte[] open(char[] password, byte[] sealed) throws GeneralSecurityException {
		if (sealed.length < 48 || !Arrays.equals(Arrays.copyOf(sealed, 4), MAGIC)) throw new GeneralSecurityException("Not a CloudVault secret block");
		byte[] salt = Arrays.copyOfRange(sealed, 4, 20), iv = Arrays.copyOfRange(sealed, 20, 32), ct = Arrays.copyOfRange(sealed, 32, sealed.length);
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
		return c.doFinal(ct);
	}
}
