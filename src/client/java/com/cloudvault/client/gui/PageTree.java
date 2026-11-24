package com.cloudvault.client.gui;

import com.cloudvault.settings.SettingsRegistry;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The shape of the settings, in one place. Main Settings branches to these; the breadcrumb follows the parent chain, so
 * CloudVault > Main Settings > Cloud > Cloud Management comes out by itself. Separate Pages hold the screens that are not settings.
 */
public final class PageTree {
	private PageTree() {}

	public static final SettingsRegistry REG = new SettingsRegistry();

	public record Entry(String label, String tip, Function<Screen, Screen> open) {}

	/** Groups of registry pages, in order. A group without children is a registry page of its own. */
	public static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
	public static final List<String> TOP = List.of("Backups", "Cloud", "Alerts", "Worlds", "Account & Devices", "Advanced", "Separate Pages");
	static {
		GROUPS.put("Backups", List.of("When", "Keeping", "Comments"));
		GROUPS.put("Cloud", List.of("Storage", "Second destination", "Cloud Sync", "Upload controls", "Encryption"));
	}

	public static final Map<String, String> TIPS = Map.of(
		"Backups", "When backups are made, how long they are kept, comments.",
		"Cloud", "Storage, second destination, sync between computers, upload controls, encryption.",
		"Alerts", "Phone, chat or e-mail messages when something goes wrong.",
		"Worlds", "Which worlds use CloudVault and their own settings.",
		"Account & Devices", "Your settings in the cloud, shared between computers.",
		"Advanced", "Master switch, tests, tools.",
		"Separate Pages", "Import, Compare, Share, View Backups, Storage usage, Worlds and Reset.");

	/** Every registry page in the order the pages are reached from Main Settings (the Advanced wizard walks this list, so it covers every setting). */
	public static List<String> allPageKeys() {
		List<String> l = new ArrayList<>();
		for (String top : TOP) {
			if (GROUPS.containsKey(top)) for (String c : GROUPS.get(top)) l.add(top + "/" + c);
			else if (REG.page(top) != null) l.add(top);
		}
		return l;
	}

	/** Opens the page with this key: "Backups" (a menu), "Backups/When" (settings), "Separate Pages", "Alerts"... */
	public static Screen open(Screen parent, String key) {
		if (key.equals("Separate Pages")) return separatePages(parent);
		if (GROUPS.containsKey(key)) return new MenuScreen(parent, key, TIPS.getOrDefault(key, ""), children(key));
		SettingsRegistry.Page p = REG.page(key);
		if (p == null) throw new IllegalArgumentException("Unknown settings page " + key);
		return new PageScreen(parent, p);
	}

	private static List<Entry> children(String group) {
		List<Entry> l = new ArrayList<>();
		for (String c : GROUPS.get(group)) l.add(new Entry(c, "", par -> open(par, group + "/" + c)));
		return l;
	}

	/** Extra screens a registry page links to (logins, lists, secrets). */
	public static List<Entry> extras(String key) {
		List<Entry> l = new ArrayList<>();
		switch (key) {
			case "Cloud/Storage" -> {
				l.add(new Entry("Connect or change cloud storage...", "Google Drive sign-in or S3 details.", CloudConnectScreen::new));
				l.add(new Entry("Cloud Management...", "See and delete what CloudVault keeps in the cloud.", CloudManageScreen::new));
			}
			case "Cloud/Second destination" -> {
				l.add(new Entry("Second destination: keys and test...", "S3 secret key, second Google account, and a test button.", MirrorScreen::new));
			}
			case "Cloud/Encryption" -> l.add(new Entry("Encryption key and password...", "Set up, change or recover the key.", EncryptionScreen::new));
			case "Alerts" -> l.add(new Entry("Alert channels (phone, Discord, e-mail...)...", "Add where alerts are sent and test them.", AlertChannelsScreen::new));
			case "Worlds" -> l.add(new Entry("World list: on/off and own settings...", "Choose per world.", WorldsScreen::new));
			case "Account & Devices" -> l.add(new Entry("CloudVault account: devices and settings files...", "Load settings from another computer, password, delete files.", AccountScreen::new));
			case "Advanced" -> {
				l.add(new Entry("Storage usage and cleanup...", "Disk use and cleanup tools.", StorageScreen::new));
				l.add(new Entry("Setup wizard...", "Quick setup or Advanced setup.", WizardChooserScreen::new));
			}
			default -> { }
		}
		return l;
	}

	public static Screen separatePages(Screen parent) {
		List<Entry> l = new ArrayList<>();
		l.add(new Entry("Import (cloud or share code)", "Bring a world from your cloud or from a share code.", ImportScreen::new));
		l.add(new Entry("Worlds and Reset", "Per-world tools and resetting a world.", WorldsScreen::new));
		l.add(new Entry("View Backups", "Browse, restore, delete, comment.", BackupBrowserScreen::new));
		l.add(new Entry("Compare two backups", "See what changed between two backups.", CompareScreen::new));
		l.add(new Entry("Share a backup", "Make a link or code someone else can use.", ShareScreen::new));
		l.add(new Entry("Storage usage", "Where the disk space goes.", StorageScreen::new));
		return new MenuScreen(parent, "Separate Pages", TIPS.get("Separate Pages"), l);
	}
}
