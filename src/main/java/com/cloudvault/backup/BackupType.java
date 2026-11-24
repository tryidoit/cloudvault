package com.cloudvault.backup;

public enum BackupType {
	MANUAL(1, Category.MANUAL), SESSION_JOIN(2, Category.SESSION), SESSION_LEAVE(2, Category.SESSION),
	GAMEPLAY(3, Category.GAMEPLAY), PERIODIC(4, Category.PERIODIC),
	/** Smart trigger: entering the Nether or End, low health, going idle. */
	EVENT(3, Category.EVENT),
	/** Taken automatically before a restore replaces a world. Never auto-deleted. */
	SAFETY(0, Category.SAFETY),
	/** Taken right before a world is reset to its latest backup. Never auto-deleted. */
	PRE_RESET(0, Category.SAFETY),
	/** Taken before a world is opened after Minecraft or the mods changed. Never auto-deleted. */
	PRE_UPDATE(0, Category.SAFETY);

	public enum Category { SESSION, PERIODIC, GAMEPLAY, MANUAL, EVENT, SAFETY }

	/** Text shown to the player. */
	public String label() { return this == PRE_RESET ? "BEFORE RESET" : this == PRE_UPDATE ? "BEFORE UPDATE" : name().replace('_', ' '); }

	/** Lower number = runs first in the queue. */
	public final int priority;
	public final Category category;
	BackupType(int priority, Category category) { this.priority = priority; this.category = category; }
}
