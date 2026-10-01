package org.matsim.episim.run.scenarios;

import org.matsim.episim.model.VirusStrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The disease import of the multi-season influenza scenario (docs/influenza-multiseasonal.md, section 4.3; decisions D3, D10;
 * assumptions A17, A30, A31).
 *
 * <p>Every season has a window, from the Monday of KW39 to the Sunday of KW20. Inside it the daily import is proportional to
 * the centred three-week mean of the weekly notifications, as in
 * {@link InfluenzaParameterisation#importSchedule(NavigableMap, double, String)}, with fractions carried from day to day.
 * A season with one modelled strain gives it the whole import. A season with two, an A strain and B/Victoria, splits it by
 * the share of the sentinel detections of each week. Every strain gets an entry for <b>every day</b> of the horizon, zero
 * outside its windows: before its first entry the code seeds one infection a day, and after its last it keeps the last
 * value.</p>
 */
public final class MultiSeasonImport {

	/** The A subtype of a season that is split against B/Victoria, named by the column of the sentinel file. */
	public enum ASubtype {
		H3N2, H1N1PDM09
	}

	/**
	 * A season of the run.
	 *
	 * @param name    label of the season in the sentinel file, for example {@code 2022/23}
	 * @param start   first day of the window
	 * @param end     last day of the window
	 * @param main    the season's A strain, or the only strain
	 * @param subtype the sentinel column that stands for {@code main}; unused without {@code b}
	 * @param b       B/Victoria if it is modelled in the season, else {@code null}; then {@code main} takes all the import
	 */
	public record Season(String name, LocalDate start, LocalDate end, VirusStrain main, ASubtype subtype, VirusStrain b) {
	}

	/** One row of the sentinel file: the detections of a week. */
	public record SentinelWeek(String season, LocalDate monday, int h3n2, int h1n1pdm09, int bVictoria) {

		int count(ASubtype subtype) {
			return subtype == ASubtype.H3N2 ? h3n2 : h1n1pdm09;
		}
	}

	/** First day of the run. */
	public static final LocalDate HORIZON_START = LocalDate.parse("2022-09-26");

	/** Last day of the run. */
	public static final LocalDate HORIZON_END = LocalDate.parse("2026-05-17");

	/** The four seasons; the windows are the Monday of KW39 to the Sunday of KW20. */
	public static final List<Season> SEASONS = List.of(
			new Season("2022/23", LocalDate.parse("2022-09-26"), LocalDate.parse("2023-05-21"),
					MultiSeasonStrains.H3N2_2022_23, ASubtype.H3N2, MultiSeasonStrains.BVIC),
			new Season("2023/24", LocalDate.parse("2023-09-25"), LocalDate.parse("2024-05-19"),
					MultiSeasonStrains.H1N1PDM09_2023_24, ASubtype.H1N1PDM09, MultiSeasonStrains.BVIC),
			new Season("2024/25", LocalDate.parse("2024-09-23"), LocalDate.parse("2025-05-18"),
					MultiSeasonStrains.H1N1PDM09_2024_25, ASubtype.H1N1PDM09, MultiSeasonStrains.BVIC),
			new Season("2025/26", LocalDate.parse("2025-09-22"), LocalDate.parse("2026-05-17"),
					MultiSeasonStrains.H3N2K_2025_26, ASubtype.H3N2, null));

	/**
	 * Weeks to pool on each side of a week for its share (three weeks in all). Weekly counts are small, zero at the start of a
	 * season, so one week alone says little.
	 */
	private static final int POOL_WEEKS_EACH_SIDE = 1;

	/** Pooled detections of the A strain and B that a week needs for a share of its own; fewer than this, it is filled in. */
	public static final int MIN_POOLED_DETECTIONS = 1;

	private MultiSeasonImport() {
	}

	/**
	 * The import of every strain for every day from {@code horizonStart} to {@code horizonEnd}.
	 *
	 * @param weeklyCases   notifications by the Monday of the reporting week; has to contain the week before the window of a
	 *                      season and the week after it
	 * @param perWeeklyCase agents per day per weekly case (D10: one value for all seasons)
	 * @param sentinel      the sentinel detections, read by {@link #readSentinel(Path)}
	 * @param source        name of the notification file, for error messages
	 * @return per strain a map with an entry for each day of the horizon, in the order in which the strains first occur in
	 * {@code seasons}
	 */
	public static Map<VirusStrain, Map<LocalDate, Integer>> importSchedules(NavigableMap<LocalDate, Integer> weeklyCases,
																		   double perWeeklyCase, List<SentinelWeek> sentinel,
																		   List<Season> seasons, LocalDate horizonStart,
																		   LocalDate horizonEnd, String source) {

		Map<VirusStrain, Map<LocalDate, Integer>> schedules = new LinkedHashMap<>();
		for (Season season : seasons) {
			schedules.computeIfAbsent(season.main(), s -> new TreeMap<>());
			if (season.b() != null)
				schedules.computeIfAbsent(season.b(), s -> new TreeMap<>());
		}

		for (Season season : seasons) {
			if (season.start().isBefore(horizonStart) || season.end().isAfter(horizonEnd))
				throw new IllegalArgumentException("The window of season " + season.name() + " is not inside the horizon");

			NavigableMap<LocalDate, Double> bShare = season.b() == null ? null
					: bShare(sentinel, season, MIN_POOLED_DETECTIONS);

			double expectedMain = 0, expectedB = 0;
			long issuedMain = 0, issuedB = 0;
			for (LocalDate day = season.start(); !day.isAfter(season.end()); day = day.plusDays(1)) {

				LocalDate week = day.with(DayOfWeek.MONDAY);
				Integer before = weeklyCases.get(week.minusWeeks(1));
				Integer current = weeklyCases.get(week);
				Integer after = weeklyCases.get(week.plusWeeks(1));
				if (before == null || current == null || after == null)
					throw new IllegalStateException(source + " lacks the week of " + week + " or a neighbour of it.");

				double driver = perWeeklyCase * (before + current + after) / 3.0;
				double shareB = bShare == null ? 0.0 : bShare.get(week);

				expectedMain += driver * (1.0 - shareB);
				long agentsMain = Math.round(expectedMain) - issuedMain;
				issuedMain += agentsMain;
				schedules.get(season.main()).put(day, (int) agentsMain);

				if (season.b() != null) {
					expectedB += driver * shareB;
					long agentsB = Math.round(expectedB) - issuedB;
					issuedB += agentsB;
					schedules.get(season.b()).put(day, (int) agentsB);
				}
			}
		}

		// an entry for every day, because the code seeds one infection a day before the first entry of a strain
		// and keeps the last value after the last one
		for (Map<LocalDate, Integer> schedule : schedules.values())
			for (LocalDate day = horizonStart; !day.isAfter(horizonEnd); day = day.plusDays(1))
				schedule.putIfAbsent(day, 0);

		return schedules;
	}

	/**
	 * The share of B/Victoria in the import for each Monday from the Monday of the window's first day to that of its last day.
	 *
	 * <p>The share of a week with a row in the sentinel file is B / (A + B) of the detections pooled over the week and its
	 * neighbours (only the A subtype of the season counts, other A subtypes are left out). A week without a row, or with fewer
	 * than {@code minPooled} detections, gets the linear interpolation in time of the nearest weeks that have a share; before
	 * the first and after the last such week the nearest share is held.</p>
	 */
	static NavigableMap<LocalDate, Double> bShare(List<SentinelWeek> sentinel, Season season, int minPooled) {

		NavigableMap<LocalDate, SentinelWeek> rows = new TreeMap<>();
		for (SentinelWeek row : sentinel)
			if (row.season().equals(season.name()))
				rows.put(row.monday(), row);

		LocalDate first = season.start().with(DayOfWeek.MONDAY);
		LocalDate last = season.end().with(DayOfWeek.MONDAY);

		NavigableMap<LocalDate, Double> known = new TreeMap<>();
		for (LocalDate week = first; !week.isAfter(last); week = week.plusWeeks(1)) {
			if (!rows.containsKey(week))
				continue;
			int a = 0, b = 0;
			for (int k = -POOL_WEEKS_EACH_SIDE; k <= POOL_WEEKS_EACH_SIDE; k++) {
				SentinelWeek row = rows.get(week.plusWeeks(k));
				if (row != null) {
					a += row.count(season.subtype());
					b += row.bVictoria();
				}
			}
			if (a + b >= Math.max(1, minPooled))
				known.put(week, (double) b / (a + b));
		}

		if (known.isEmpty())
			throw new IllegalStateException("The sentinel file has no detections of season " + season.name());

		NavigableMap<LocalDate, Double> share = new TreeMap<>();
		for (LocalDate week = first; !week.isAfter(last); week = week.plusWeeks(1)) {
			Double exact = known.get(week);
			if (exact != null) {
				share.put(week, exact);
				continue;
			}
			Map.Entry<LocalDate, Double> previous = known.lowerEntry(week);
			Map.Entry<LocalDate, Double> next = known.higherEntry(week);
			if (previous == null)
				share.put(week, next.getValue());
			else if (next == null)
				share.put(week, previous.getValue());
			else {
				double span = ChronoUnit.DAYS.between(previous.getKey(), next.getKey());
				double part = ChronoUnit.DAYS.between(previous.getKey(), week);
				share.put(week, previous.getValue() + part * (next.getValue() - previous.getValue()) / span);
			}
		}
		return share;
	}

	/**
	 * Reads the sentinel file: comment lines start with {@code #}, the header names the columns {@code season}, {@code monday},
	 * {@code A_H3N2}, {@code A_H1N1pdm09} and {@code B_Victoria}.
	 */
	public static List<SentinelWeek> readSentinel(Path file) throws IOException {
		List<SentinelWeek> weeks = new ArrayList<>();
		String[] header = null;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#"))
				continue;
			String[] cols = line.split(",", -1);
			if (header == null) {
				header = cols;
				continue;
			}
			weeks.add(new SentinelWeek(cols[column(header, "season")], LocalDate.parse(cols[column(header, "monday")]),
					Integer.parseInt(cols[column(header, "A_H3N2")]), Integer.parseInt(cols[column(header, "A_H1N1pdm09")]),
					Integer.parseInt(cols[column(header, "B_Victoria")])));
		}
		if (header == null)
			throw new IllegalArgumentException("No header in " + file);
		return weeks;
	}

	private static int column(String[] header, String name) {
		for (int i = 0; i < header.length; i++)
			if (header[i].equals(name))
				return i;
		throw new IllegalArgumentException("Column '" + name + "' missing in the sentinel file");
	}
}
