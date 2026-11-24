package com.cloudvault.util;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZonedDateTime;

/** Interval maths. Months/years are calendar-aware (never 30/365 days). */
public final class TimeUtil {
	private TimeUtil() {}

	public enum Unit {
		MINUTES("Minutes"), HOURS("Hours"), DAYS("Days"), WEEKS("Weeks"), MONTHS("Months"), YEARS("Years");
		public final String label;
		Unit(String label) { this.label = label; }
		public boolean isCalendar() { return this == MONTHS || this == YEARS; }
	}

	public static final Unit[] PERIODIC_UNITS = Unit.values();
	public static final Unit[] GAMEPLAY_UNITS = { Unit.MINUTES, Unit.HOURS, Unit.DAYS, Unit.WEEKS };
	private static final int MAX_AMOUNT = 100_000;

	public static boolean validAmount(int amount, Unit unit) {
		if (amount <= 0 || amount > MAX_AMOUNT) return false;
		return switch (unit) {
			case MINUTES, HOURS, DAYS, WEEKS -> true;
			case MONTHS -> amount <= 1200;
			case YEARS -> amount <= 100;
		};
	}

	/** Parses user text; returns -1 when invalid (not a number, <=0, overflow). */
	public static int parseAmount(String text, Unit unit) {
		try {
			int v = Integer.parseInt(text.trim());
			return validAmount(v, unit) ? v : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Next due time after {@code from}. Throws IllegalArgumentException on invalid input. */
	public static ZonedDateTime addInterval(ZonedDateTime from, int amount, Unit unit) {
		if (!validAmount(amount, unit)) throw new IllegalArgumentException("Invalid interval: " + amount + " " + unit);
		try {
			return switch (unit) {
				case MINUTES -> from.plusMinutes(amount);
				case HOURS -> from.plusHours(amount);
				case DAYS -> from.plusDays(amount);
				case WEEKS -> from.plusWeeks(amount);
				case MONTHS -> from.plusMonths(amount);
				case YEARS -> from.plusYears(amount);
			};
		} catch (DateTimeException | ArithmeticException e) {
			throw new IllegalArgumentException("Interval out of range", e);
		}
	}

	/** Fixed duration for non-calendar units (used by the gameplay timer). */
	public static Duration toDuration(int amount, Unit unit) {
		if (unit.isCalendar() || !validAmount(amount, unit)) throw new IllegalArgumentException("Not a fixed-length unit: " + unit);
		return switch (unit) {
			case MINUTES -> Duration.ofMinutes(amount);
			case HOURS -> Duration.ofHours(amount);
			case DAYS -> Duration.ofDays(amount);
			default -> Duration.ofDays(7L * amount);
		};
	}

	public static String formatDuration(Duration d) {
		long s = Math.max(0, d.getSeconds());
		long days = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
		if (days > 0) return days + "d " + h + "h";
		if (h > 0) return h + "h " + m + "m";
		return m + "m " + (s % 60) + "s";
	}
}
