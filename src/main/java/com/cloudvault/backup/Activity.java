package com.cloudvault.backup;

/** What CloudVault is doing right now (for the main screen). Cheap, lock-free; written by the worker, read by the GUI. */
public class Activity {
	public enum Phase {
		IDLE("Idle"), SAVING_WORLD("Saving world"), PREPARING("Preparing backup"), COPYING("Copying files"), COMPRESSING("Compressing"),
		VERIFYING("Verifying backup"), TESTING("Testing restore"), UPLOADING("Uploading"), VERIFYING_CLOUD("Verifying cloud copy"), RESTORING("Restoring");
		public final String label;
		Phase(String l) { label = l; }
	}

	private volatile Phase phase = Phase.IDLE;
	private volatile String world = "";
	private volatile long lastSuccessAt = 0;
	private volatile String lastSuccess = "", lastProblem = "";

	public void set(Phase p, String worldName) { this.phase = p; this.world = worldName == null ? "" : worldName; }
	public void idle() { this.phase = Phase.IDLE; this.world = ""; }
	public void succeeded(String what) { lastSuccessAt = System.currentTimeMillis(); lastSuccess = what; lastProblem = ""; }
	public void failed(String what) { lastProblem = what; }

	public Phase phase() { return phase; }
	public String describe() { return phase == Phase.IDLE ? "Idle" : phase.label + (world.isEmpty() ? "" : " (" + world + ")"); }
	public long lastSuccessAt() { return lastSuccessAt; }
	public String lastSuccess() { return lastSuccess; }
	public String lastProblem() { return lastProblem; }
}
