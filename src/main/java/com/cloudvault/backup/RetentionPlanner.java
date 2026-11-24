package com.cloudvault.backup;

import com.cloudvault.config.RetentionPolicy;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Predicate;

/** Pure decision logic for advanced retention (easy to test). Returns the ids that must be KEPT. */
public final class RetentionPlanner {
	private RetentionPlanner() {}

	/** @param newestFirst one category of one world, newest first. Non-good backups are always kept. */
	public static Set<String> keep(List<BackupMetadata> newestFirst, RetentionPolicy p, ZonedDateTime now, Predicate<BackupMetadata> isGood) {
		Set<String> keep = new HashSet<>();
		List<BackupMetadata> good = new ArrayList<>();
		for (BackupMetadata m : newestFirst) { if (isGood.test(m)) good.add(m); else keep.add(m.backupId); }
		if (good.isEmpty()) return keep;
		keep.add(good.get(0).backupId); // the newest verified backup is always safe

		for (int i = 0; i < Math.min(p.keepNewest, good.size()); i++) keep.add(good.get(i).backupId);

		ZoneId zone = now.getZone();
		LocalDate today = now.toLocalDate();
		Set<LocalDate> days = new HashSet<>(), weeks = new HashSet<>();
		Set<YearMonth> months = new HashSet<>();
		LocalDate dayLimit = today.minusDays(Math.max(0, p.dailyDays - 1L));
		LocalDate weekLimit = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(Math.max(0, p.weeklyWeeks - 1L));
		YearMonth monthLimit = YearMonth.from(today).minusMonths(Math.max(0, p.monthlyMonths - 1L));
		for (BackupMetadata m : good) { // newest first => the first one seen in a bucket is the newest of it
			LocalDate d = Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalDate();
			if (p.dailyDays > 0 && !d.isBefore(dayLimit) && days.add(d)) keep.add(m.backupId);
			LocalDate wk = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			if (p.weeklyWeeks > 0 && !wk.isBefore(weekLimit) && weeks.add(wk)) keep.add(m.backupId);
			YearMonth ym = YearMonth.from(d);
			if (p.monthlyMonths > 0 && !ym.isBefore(monthLimit) && months.add(ym)) keep.add(m.backupId);
		}
		return keep;
	}
}
