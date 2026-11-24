package com.cloudvault.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything private that is not a login token: alert webhooks, phone numbers, e-mail passwords, second-destination keys.
 * One file (auth/secrets.json, owner-only where the system allows it). config.json never holds any of it.
 */
public class SecretStore {
	private static final Gson GSON = new Gson();
	private final Path file;
	private final Map<String, String> values = new TreeMap<>();

	public SecretStore(Path authDir) {
		this.file = authDir.resolve("secrets.json");
		try {
			if (Files.exists(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) m.forEach((k, v) -> { if (k != null && v != null) values.put(k, v); });
			}
		} catch (Exception ignored) { /* unreadable: start empty, never crash */ }
	}

	public synchronized String get(String key) { return values.getOrDefault(key, ""); }
	public synchronized boolean has(String key) { return !values.getOrDefault(key, "").isEmpty(); }

	public synchronized void set(String key, String value) {
		if (value == null || value.isBlank()) values.remove(key); else values.put(key, value.trim());
		save();
	}

	/** Removes every key that starts with the prefix (e.g. a deleted alert channel). */
	public synchronized void removePrefix(String prefix) { if (values.keySet().removeIf(k -> k.startsWith(prefix))) save(); }

	public synchronized Map<String, String> snapshot() { return new TreeMap<>(values); }

	public synchronized void putAll(Map<String, String> m) { m.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) values.put(k, v); }); save(); }

	private void save() {
		try { FileUtil.writeSecret(file, GSON.toJson(values)); } catch (IOException ignored) { /* best effort */ }
	}
}
