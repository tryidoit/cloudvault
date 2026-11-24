package com.cloudvault.notification;

/** Core code reports events here; the client module turns them into Minecraft toasts. */
public interface NotificationSink {
	enum Level { INFO, SUCCESS, WARNING, ERROR }
	void notify(Level level, String title, String message);
	NotificationSink NONE = (l, t, m) -> {};
}
