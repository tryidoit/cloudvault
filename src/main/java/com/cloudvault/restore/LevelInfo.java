package com.cloudvault.restore;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Minimal, bounded reader for level.dat (gzip NBT): enough to read the Minecraft version a world was last saved with. */
public final class LevelInfo {
	private LevelInfo() {}

	private static final int MAX_BYTES = 16 * 1024 * 1024; // decompressed limit (guards against a gzip bomb)
	private static final int MAX_DEPTH = 32;

	/** True if the file is gzip NBT with a root compound. */
	public static boolean looksValid(Path levelDat) { return readTree(levelDat) != null; }

	/** Data.Version.Name (for example "1.21.4"), or null when missing/unreadable (very old worlds, damaged files). */
	public static String readVersion(Path levelDat) {
		Object root = readTree(levelDat);
		if (!(root instanceof java.util.Map<?, ?> r)) return null;
		Object data = r.get("Data");
		if (!(data instanceof java.util.Map<?, ?> d)) return null;
		Object ver = d.get("Version");
		if (!(ver instanceof java.util.Map<?, ?> v)) return null;
		return v.get("Name") instanceof String n && !n.isBlank() ? n : null;
	}

	/** The simple values of level.dat's "Data" (LevelName, Time, DayTime, LastPlayed, GameType...) or an empty map. */
	public static java.util.Map<String, Object> readData(Path levelDat) {
		Object root = readTree(levelDat);
		if (root instanceof java.util.Map<?, ?> r && r.get("Data") instanceof java.util.Map<?, ?> d) {
			java.util.Map<String, Object> out = new java.util.HashMap<>();
			d.forEach((k, v) -> { if (k instanceof String ks && (v instanceof Number || v instanceof String)) out.put(ks, v); });
			return out;
		}
		return java.util.Map.of();
	}

	private static Object readTree(Path file) {
		try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			byte[] all = in.readNBytes(MAX_BYTES + 1);
			if (all.length > MAX_BYTES || all.length < 3) return null;
			DataInputStream d = new DataInputStream(new ByteArrayInputStream(all));
			if (d.readByte() != 10) return null; // root must be a compound
			d.readUTF();
			return readValue(d, 10, 0);
		} catch (Exception e) { return null; }
	}

	private static Object readValue(DataInputStream d, int type, int depth) throws IOException {
		if (depth > MAX_DEPTH) throw new IOException("too deep");
		switch (type) {
			case 1: return d.readByte();
			case 2: return d.readShort();
			case 3: return d.readInt();
			case 4: return d.readLong();
			case 5: return d.readFloat();
			case 6: return d.readDouble();
			case 7: skip(d, 1L * checkLen(d.readInt())); return null;
			case 8: return d.readUTF();
			case 9: {
				int t = d.readByte(), n = checkLen(d.readInt());
				if (t == 0) return null;
				for (int i = 0; i < n; i++) readValue(d, t, depth + 1);
				return null;
			}
			case 10: {
				java.util.Map<String, Object> m = new java.util.HashMap<>();
				while (true) {
					int t = d.readByte();
					if (t == 0) return m;
					String name = d.readUTF();
					m.put(name, readValue(d, t, depth + 1));
				}
			}
			case 11: skip(d, 4L * checkLen(d.readInt())); return null;
			case 12: skip(d, 8L * checkLen(d.readInt())); return null;
			default: throw new IOException("bad tag");
		}
	}

	private static int checkLen(int n) throws IOException { if (n < 0 || n > MAX_BYTES) throw new IOException("bad length"); return n; }
	private static void skip(DataInputStream d, long n) throws IOException { if (d.skipBytes((int) Math.min(n, Integer.MAX_VALUE)) < n) throw new EOFException(); }
}
