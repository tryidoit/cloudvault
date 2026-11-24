package com.cloudvault.config;

/**
 * Advanced (combined) retention, applied separately to each backup category. A backup is kept if ANY rule keeps it:
 * newest N, newest-of-each-day for D days, newest-of-each-week for W weeks, newest-of-each-month for M months.
 * 0 disables a rule. Backups that are not verified yet are never deleted.
 */
public class RetentionPolicy {
	public boolean enabled = false;
	public int keepNewest = 10;     // 0..100
	public int dailyDays = 30;      // 0..365
	public int weeklyWeeks = 12;    // 0..260
	public int monthlyMonths = 12;  // 0..120

	public void sanitize() {
		keepNewest = clamp(keepNewest, 0, 100);
		dailyDays = clamp(dailyDays, 0, 365);
		weeklyWeeks = clamp(weeklyWeeks, 0, 260);
		monthlyMonths = clamp(monthlyMonths, 0, 120);
		if (keepNewest == 0 && dailyDays == 0 && weeklyWeeks == 0 && monthlyMonths == 0) keepNewest = 1; // never keep nothing
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		if (keepNewest > 0) sb.append("newest ").append(keepNewest);
		if (dailyDays > 0) sb.append(sb.length() > 0 ? ", " : "").append("1/day for ").append(dailyDays).append("d");
		if (weeklyWeeks > 0) sb.append(sb.length() > 0 ? ", " : "").append("1/week for ").append(weeklyWeeks).append("w");
		if (monthlyMonths > 0) sb.append(sb.length() > 0 ? ", " : "").append("1/month for ").append(monthlyMonths).append("mo");
		return sb.toString();
	}

	private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
