package com.cloudvault.cloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;

/** Upload speed limit (Settings > Upload controls). 0 = unlimited. Applies to every upload that starts after the change. */
public final class Throttle {
	private Throttle() {}
	private static volatile long bytesPerSecond = 0;

	public static void setLimitKBps(int kbps) { bytesPerSecond = kbps <= 0 ? 0 : kbps * 1024L; }
	public static long limit() { return bytesPerSecond; }

	public static BodyPublisher file(Path f) throws IOException {
		if (bytesPerSecond == 0) return BodyPublishers.ofFile(f);
		return BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> { try { return limited(Files.newInputStream(f)); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }), Files.size(f));
	}

	public static BodyPublisher bytes(byte[] b) {
		if (bytesPerSecond == 0) return BodyPublishers.ofByteArray(b);
		return BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() -> limited(new ByteArrayInputStream(b))), b.length);
	}

	/** Sleeps between reads so the average rate stays at the limit (re-read on every read, so a changed limit applies at once). */
	public static InputStream limited(InputStream in) {
		return new InputStream() {
			private final long start = System.nanoTime();
			private long sent = 0;
			@Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one, 0, 1); return n <= 0 ? -1 : one[0] & 0xFF; }
			@Override public int read(byte[] b, int off, int len) throws IOException {
				int n = in.read(b, off, Math.min(len, 16 * 1024));
				if (n > 0) {
					sent += n;
					long limit = bytesPerSecond;
					if (limit > 0) {
						long dueNanos = sent * 1_000_000_000L / limit, ahead = dueNanos - (System.nanoTime() - start);
						if (ahead > 2_000_000L) try { Thread.sleep(ahead / 1_000_000L, (int) (ahead % 1_000_000L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
					}
				}
				return n;
			}
			@Override public void close() throws IOException { in.close(); }
		};
	}
}
