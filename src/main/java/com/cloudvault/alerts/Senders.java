package com.cloudvault.alerts;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/** One small HTTPS call per service. Only https:// is accepted (a plain http address only for a service on this computer). */
final class Senders {
	private Senders() {}
	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

	static void requireHttps(String url, String what) throws IOException {
		if (url == null || url.isBlank()) throw new IOException(what + " is missing");
		String u = url.trim().toLowerCase(Locale.ROOT);
		boolean local = u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1");
		if (!u.startsWith("https://") && !local) throw new IOException(what + " must start with https://");
	}

	private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

	static HttpResponse<String> send(HttpRequest r) throws IOException {
		try {
			HttpResponse<String> res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() / 100 != 2) throw new IOException("The service answered " + res.statusCode() + (res.body() != null && res.body().length() < 200 ? ": " + res.body().replaceAll("\\s+", " ") : ""));
			return res;
		} catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
	}

	private static HttpRequest.Builder req(String url) { return HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(20)); }

	static void postJson(String url, JsonObject body) throws IOException {
		send(req(url).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
	}

	static void discord(String url, String title, String text) throws IOException {
		requireHttps(url, "The Discord webhook address");
		JsonObject o = new JsonObject(); o.addProperty("content", clip("**" + title + "**\n" + text, 1900)); o.addProperty("username", "CloudVault");
		postJson(url, o);
	}

	static void slack(String url, String title, String text) throws IOException {
		requireHttps(url, "The Slack webhook address");
		JsonObject o = new JsonObject(); o.addProperty("text", clip("*" + title + "*\n" + text, 3000));
		postJson(url, o);
	}

	static void webhook(String url, String title, String text) throws IOException {
		requireHttps(url, "The web hook address");
		JsonObject o = new JsonObject(); o.addProperty("title", title); o.addProperty("message", text); o.addProperty("text", title + "\n" + text); o.addProperty("time", java.time.Instant.now().toString());
		postJson(url, o);
	}

	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

	static void telegram(String token, String chatId, String title, String text) throws IOException {
		if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) throw new IOException("Telegram bot token and chat id are needed");
		String body = "chat_id=" + enc(chatId.trim()) + "&text=" + enc(clip(title + "\n" + text, 3500));
		send(req("https://api.telegram.org/bot" + token.trim() + "/sendMessage").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	static void ntfy(String server, String topic, String token, String title, String text) throws IOException {
		if (topic == null || topic.isBlank()) throw new IOException("The ntfy topic is missing");
		String base = server == null || server.isBlank() ? "https://ntfy.sh" : server.trim().replaceAll("/+$", "");
		requireHttps(base, "The ntfy server address");
		HttpRequest.Builder b = req(base + "/" + enc(topic.trim())).header("Title", title.replaceAll("[^\\x20-\\x7E]", "")).header("Priority", "high").header("Tags", "warning")
			.POST(HttpRequest.BodyPublishers.ofString(clip(text, 3000), StandardCharsets.UTF_8));
		if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token.trim());
		send(b.build());
	}

	static void pushover(String token, String user, String title, String text) throws IOException {
		if (token == null || token.isBlank() || user == null || user.isBlank()) throw new IOException("Pushover app token and user key are needed");
		String body = "token=" + enc(token.trim()) + "&user=" + enc(user.trim()) + "&title=" + enc(title) + "&message=" + enc(clip(text, 1000)) + "&priority=1";
		send(req("https://api.pushover.net/1/messages.json").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	/** SMS needs a sending service; CloudVault uses Twilio (you need a Twilio account and number). The phone number alone cannot send a text. */
	static void twilio(String sid, String authToken, String from, String to, String text) throws IOException {
		if (sid == null || sid.isBlank() || authToken == null || authToken.isBlank() || from == null || from.isBlank() || to == null || to.isBlank()) throw new IOException("Twilio account id, auth token, your Twilio number and your phone number are needed");
		String body = "From=" + enc(from.trim()) + "&To=" + enc(to.trim()) + "&Body=" + enc(clip(text, 300));
		String auth = Base64.getEncoder().encodeToString((sid.trim() + ":" + authToken.trim()).getBytes(StandardCharsets.UTF_8));
		send(req("https://api.twilio.com/2010-04-01/Accounts/" + enc(sid.trim()) + "/Messages.json").header("Authorization", "Basic " + auth)
			.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}
}
