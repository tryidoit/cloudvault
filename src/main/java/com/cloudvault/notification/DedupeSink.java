package com.cloudvault.notification;

import java.util.HashMap;
import java.util.Map;

/** Stops the same warning/error from popping up again and again (for example every retry while offline). */
public class DedupeSink implements NotificationSink {
	private final NotificationSink next;
	private final long windowMs;
	private final Map<String, Long> last = new HashMap<>();

	public DedupeSink(NotificationSink next, long windowMs) { this.next = next; this.windowMs = windowMs; }

	@Override public void notify(Level level, String title, String message) {
		if (level == Level.WARNING || level == Level.ERROR) {
			String key = level + "|" + title + "|" + message;
			long now = System.currentTimeMillis();
			synchronized (last) {
				Long t = last.get(key);
				if (t != null && now - t < windowMs) return;
				last.put(key, now);
				if (last.size() > 50) last.values().removeIf(v -> now - v >= windowMs);
			}
		}
		next.notify(level, title, message);
	}
}
