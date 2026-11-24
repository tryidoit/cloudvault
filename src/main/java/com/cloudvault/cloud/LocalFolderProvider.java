package com.cloudvault.cloud;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * A folder as a "cloud": a NAS share, a USB drive, or the folder of a Dropbox / OneDrive / iCloud / Syncthing client (which then
 * uploads it for you). Same layout as the real clouds: CloudVault/<world>/backup-....zip. Used as the SECOND destination.
 */
public class LocalFolderProvider implements CloudProvider {
	private static final Gson GSON = new Gson();
	private final java.util.function.Supplier<String> pathSupplier;
	private final String id;

	public LocalFolderProvider(String id, java.util.function.Supplier<String> pathSupplier) { this.id = id; this.pathSupplier = pathSupplier; }

	private Path root() throws CloudException {
		String p = pathSupplier.get();
		if (p == null || p.isBlank()) throw new CloudException(CloudException.Kind.AUTH, "No folder chosen for the second destination");
		Path r = Path.of(p.trim()).toAbsolutePath().normalize();
		if (!Files.isDirectory(r)) throw new CloudException(CloudException.Kind.NETWORK, "The folder is not available: " + r.getFileName() + " (is the drive connected?)");
		return r;
	}

	@Override public String id() { return id; }
	@Override public boolean isAuthenticated() { try { root(); return true; } catch (CloudException e) { return false; } }
	@Override public void authenticate() {}
	@Override public void signOut() {}
	@Override public Status testConnection() {
		try {
			Path r = root();
			Path t = r.resolve(".cloudvault-test-" + UUID.randomUUID());
			Files.writeString(t, "ok"); Files.delete(t);
			return new Status(true, r.toString(), "");
		} catch (CloudException e) { return new Status(false, null, e.getMessage()); }
		catch (IOException e) { return new Status(false, null, "Cannot write to that folder"); }
	}

	private static String safe(String s) { return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"); }

	private Path dirFor(String area, String folder) throws CloudException {
		String top = switch (area) { case "backup" -> "CloudVault"; case "sync" -> "CloudVault Sync"; case "account" -> "CloudVault Account"; case "shares" -> "CloudVault Shares"; default -> "CloudVault " + safe(area); };
		return folder == null ? root().resolve(top) : root().resolve(top).resolve(safe(folder));
	}

	private RemoteFile put(String area, Path file, String name, String folder, Map<String, String> props) throws IOException {
		Path dir = dirFor(area, folder);
		Files.createDirectories(dir);
		Path target = dir.resolve(safe(name)), part = dir.resolve(safe(name) + ".part");
		Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
		try { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
		catch (AtomicMoveNotSupportedException e) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); }
		Files.writeString(dir.resolve(safe(name) + ".props"), GSON.toJson(props));
		return remote(target, folder);
	}

	@SuppressWarnings("unchecked")
	private RemoteFile remote(Path p, String folder) throws IOException {
		Map<String, String> props = new HashMap<>();
		Path pp = p.resolveSibling(p.getFileName() + ".props");
		if (Files.exists(pp)) { Map<String, String> m = GSON.fromJson(Files.readString(pp), Map.class); if (m != null) props.putAll(m); }
		props.put("cv_folder", folder == null ? p.getParent().getFileName().toString() : folder);
		return new RemoteFile(root().relativize(p).toString().replace('\\', '/'), p.getFileName().toString(), Files.size(p), com.cloudvault.util.HashUtil.hashFile(p).md5(), props);
	}

	@Override public RemoteFile uploadBackup(Path f, String name, String folder, Map<String, String> props) throws IOException { return put("backup", f, name, folder, props); }
	@Override public RemoteFile putFile(String area, String folder, String name, Path f, Map<String, String> props) throws IOException { return put(area, f, name, folder, props); }

	private Path resolveId(String id) throws IOException {
		Path r = root(), p = r.resolve(id).normalize();
		if (!p.startsWith(r)) throw new CloudException(CloudException.Kind.OTHER, "Bad file id");
		return p;
	}

	@Override public void downloadBackup(String remoteId, Path target) throws IOException {
		Path src = resolveId(remoteId);
		if (!Files.isRegularFile(src)) throw new CloudException(CloudException.Kind.OTHER, "notfound");
		Files.createDirectories(target.toAbsolutePath().getParent());
		Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private List<RemoteFile> list(String area, String folder, String prefix) throws IOException {
		List<RemoteFile> out = new ArrayList<>();
		Path dir = dirFor(area, folder);
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) s::iterator) {
				String n = p.getFileName().toString();
				if (Files.isRegularFile(p) && n.startsWith(prefix) && !n.endsWith(".props") && !n.endsWith(".part")) out.add(remote(p, folder));
			}
		}
		return out;
	}

	@Override public List<RemoteFile> listBackups(String folder) throws IOException { return list("backup", folder, "backup-"); }
	@Override public List<RemoteFile> listFiles(String area, String folder, String prefix) throws IOException { return list(area, folder, prefix); }

	@Override public void deleteBackup(String remoteId) throws IOException {
		Path p = resolveId(remoteId);
		Files.deleteIfExists(p);
		Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".props"));
	}

	@Override public StorageInfo storageInfo() throws IOException {
		long used = 0;
		for (RemoteFile f : list("backup", null, "backup-")) used += f.size();
		return new StorageInfo(used, -1);
	}

	@Override public String folderUrl() throws IOException { return root().toUri().toString(); }
}
