package com.cloudvault.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stable world ids WITHOUT touching the world folder: cloudvault/worlds.json maps folder name -> UUID.
 * Limitation: renaming a world folder outside CloudVault creates a new id.
 */
public class WorldUtil {
	private static final Gson GSON = new Gson();
	private final Path file;
	private final Map<String, String> ids = new HashMap<>();

	public WorldUtil(Path root) {
		this.file = root.resolve("worlds.json");
		try {
			if (Files.exists(file)) {
				Map<String, String> m = GSON.fromJson(Files.readString(file), new TypeToken<Map<String, String>>() {}.getType());
				if (m != null) m.forEach((folder, id) -> { // worlds.json could be edited: only plain folder names and ids are accepted
					if (folder != null && id != null && !folder.isEmpty() && !folder.equals(".") && !folder.equals("..") && !folder.matches(".*[/\\\\:\\p{Cntrl}].*") && id.matches("[A-Za-z0-9-]{1,64}")) ids.put(folder, id);
				});
			}
		} catch (Exception ignored) {}
	}

	public synchronized String folderFor(String worldId) {
		return ids.entrySet().stream().filter(e -> e.getValue().equals(worldId)).map(Map.Entry::getKey).findFirst().orElse(null);
	}

	/** The id of a world folder if it already has one (does not create one). */
	public synchronized String idIfKnown(String folderName) { return ids.get(folderName); }

	/** A known world id starting with the given prefix (used when importing old cloud backups), or null. */
	public synchronized String idStartingWith(String prefix) {
		return ids.values().stream().filter(v -> v.startsWith(prefix)).findFirst().orElse(null);
	}

	public synchronized String idFor(String folderName) {
		String known = ids.get(folderName);
		if (known != null) return known; // nothing new: no disk write
		String id = UUID.randomUUID().toString();
		ids.put(folderName, id);
		try { FileUtil.writeAtomic(file, GSON.toJson(ids)); } catch (IOException ignored) {}
		return id;
	}
}
