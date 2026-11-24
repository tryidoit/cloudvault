package com.cloudvault.alerts;

import com.cloudvault.CloudVault;
import com.cloudvault.config.AlertSettings;
import com.cloudvault.config.CloudVaultConfig;
import com.cloudvault.util.DeviceInfo;
import com.cloudvault.util.ErrorText;
import com.cloudvault.util.SecretStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends failure alerts outside the game: Discord, Slack, Telegram, ntfy (phone push), Pushover, SMS (via Twilio), e-mail, or any webhook.
 * Never blocks a backup: sending happens on its own thread, failures are only logged. The same alert is not repeated within the set time.
 */
public class AlertManager {
	public enum Event { BACKUP_FAILED, UPLOAD_FAILED, NO_RECENT_BACKUP, LOW_SPACE, SYNC_PROBLEM, RESTORE_TEST_FAILED, WORLD_TAKEOVER, TEST }

	/** Channel types and the fields each one needs (stored in the secret store as alert.<channelId>.<field>). */
	public static final Map<String, List<String>> FIELDS = Map.of(
		"discord", List.of("url"),
		"slack", List.of("url"),
		"webhook", List.of("url"),
		"telegram", List.of("token", "chatId"),
		"ntfy", List.of("server", "topic", "token"),
		"pushover", List.of("token", "user"),
		"sms", List.of("sid", "authToken", "from", "to"),
		"email", List.of("host", "port", "security", "username", "password", "from", "to"));

	public static final List<String> TYPES = List.of("discord", "slack", "telegram", "ntfy", "pushover", "sms", "email", "webhook");

	public static String typeLabel(String t) {
		return switch (t) {
			case "discord" -> "Discord"; case "slack" -> "Slack"; case "telegram" -> "Telegram"; case "ntfy" -> "ntfy (phone push)";
			case "pushover" -> "Pushover (phone push)"; case "sms" -> "SMS text message"; case "email" -> "E-mail"; default -> "Web hook";
		};
	}

	/** Which fields are secret (shown as stars in the screen). */
	public static boolean isSecretField(String f) { return List.of("url", "token", "authToken", "password", "user", "sid").contains(f); }

	private final CloudVaultConfig config;
	private final SecretStore secrets;
	private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "CloudVault-Alerts"); t.setDaemon(true); return t; });
	private final Map<String, Long> lastSent = new HashMap<>();
	private volatile String mcVersion = "";

	public AlertManager(CloudVaultConfig config, SecretStore secrets) { this.config = config; this.secrets = secrets; }

	public void setMcVersion(String v) { mcVersion = v == null ? "" : v; }

	private boolean wants(Event e) {
		AlertSettings a = config.alerts;
		return switch (e) {
			case BACKUP_FAILED -> a.onBackupFailed; case UPLOAD_FAILED -> a.onUploadFailed; case NO_RECENT_BACKUP -> a.onNoRecentBackup;
			case LOW_SPACE -> a.onLowSpace; case SYNC_PROBLEM -> a.onSyncProblem; case RESTORE_TEST_FAILED -> a.onRestoreTestFailed;
			case WORLD_TAKEOVER -> a.onWorldTakeover; case TEST -> true;
		};
	}

	/** Fire and forget. Does nothing when alerts are off, the event is not wanted, or the same alert was sent recently. */
	public void send(Event event, String title, String message) {
		AlertSettings a = config.alerts;
		if (!a.enabled || !wants(event)) return;
		String key = event + "|" + title;
		synchronized (lastSent) {
			long now = System.currentTimeMillis(), last = lastSent.getOrDefault(key, 0L);
			if (now - last < a.minMinutesBetween * 60_000L) return;
			lastSent.put(key, now);
		}
		String text = ErrorText.redact(message == null ? "" : message) + "\n- " + DeviceInfo.describe(config.account.deviceName, mcVersion);
		for (AlertSettings.Channel ch : List.copyOf(a.channels)) {
			if (!ch.enabled) continue;
			pool.execute(() -> {
				try { deliver(ch, "CloudVault: " + title, text); }
				catch (Exception e) { CloudVault.LOGGER.warn("Alert to {} failed: {}", ch.type, ErrorText.redact(String.valueOf(e.getMessage()))); }
			});
		}
	}

	/** Sends a test message now and reports what happened (blocking: call from a background thread). */
	public String test(AlertSettings.Channel ch) {
		try { deliver(ch, "CloudVault: test", "This is a test alert from CloudVault.\n- " + DeviceInfo.describe(config.account.deviceName, mcVersion)); return "Sent. Check your " + typeLabel(ch.type) + "."; }
		catch (Exception e) { return "Failed: " + ErrorText.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
	}

	private Map<String, String> fields(AlertSettings.Channel ch) {
		Map<String, String> m = new HashMap<>();
		for (String f : FIELDS.getOrDefault(ch.type, List.of())) m.put(f, secrets.get("alert." + ch.id + "." + f));
		return m;
	}

	public String field(AlertSettings.Channel ch, String f) { return secrets.get("alert." + ch.id + "." + f); }
	public void setField(AlertSettings.Channel ch, String f, String v) { secrets.set("alert." + ch.id + "." + f, v); }

	public AlertSettings.Channel addChannel(String type) {
		AlertSettings.Channel ch = new AlertSettings.Channel(); ch.type = type; ch.name = typeLabel(type);
		config.alerts.channels.add(ch);
		return ch;
	}

	public void removeChannel(AlertSettings.Channel ch) { config.alerts.channels.remove(ch); secrets.removePrefix("alert." + ch.id + "."); }

	/** Is every needed field filled in? (ntfy token/server and e-mail user/password/security/port are optional.) */
	public boolean complete(AlertSettings.Channel ch) {
		Map<String, String> f = fields(ch);
		for (String k : FIELDS.getOrDefault(ch.type, List.of())) {
			boolean optional = (ch.type.equals("ntfy") && (k.equals("token") || k.equals("server"))) || (ch.type.equals("email") && List.of("username", "password", "security", "port").contains(k));
			if (!optional && f.getOrDefault(k, "").isBlank()) return false;
		}
		return true;
	}

	void deliver(AlertSettings.Channel ch, String title, String text) throws Exception {
		Map<String, String> f = fields(ch);
		switch (ch.type) {
			case "discord" -> Senders.discord(f.get("url"), title, text);
			case "slack" -> Senders.slack(f.get("url"), title, text);
			case "webhook" -> Senders.webhook(f.get("url"), title, text);
			case "telegram" -> Senders.telegram(f.get("token"), f.get("chatId"), title, text);
			case "ntfy" -> Senders.ntfy(f.get("server"), f.get("topic"), f.get("token"), title, text);
			case "pushover" -> Senders.pushover(f.get("token"), f.get("user"), title, text);
			case "sms" -> Senders.twilio(f.get("sid"), f.get("authToken"), f.get("from"), f.get("to"), title + "\n" + text);
			case "email" -> Smtp.send(f.get("host"), f.get("port"), f.get("security"), f.get("username"), f.get("password"), f.get("from"), f.get("to"), title, text);
			default -> throw new IllegalArgumentException("Unknown alert type");
		}
	}

	public void shutdown() { pool.shutdown(); }
}
