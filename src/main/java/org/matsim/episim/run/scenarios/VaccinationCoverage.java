package org.matsim.episim.run.scenarios;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The seasonal influenza vaccination coverage by age band of one district, read from {@code vaccination-coverage-2022-26.tsv}
 * of a multi-season scenario (docs/influenza-multiseasonal.md, sections 7.5 and 7.6; decision D19; assumptions A41 to A43).
 *
 * <p>The file is written by {@code Scenarios/Cologne-multiseason/build-vaccination-data.py} from the RKI KV-Impfsurveillance. For
 * every season it has a row per age band; {@code coverage_band} is the percent of all persons of the band who are vaccinated in
 * the season. A season without data of its own carries the values of an earlier one (A42); {@link #sourceSeason} says which.</p>
 */
public final class VaccinationCoverage {

	/** The age bands of the file, youngest first. */
	public static final List<String> BANDS = List.of("18-29", "30-39", "40-49", "50-59", "60-69", "70-79", "80+");

	/** The first and the last age of a band; the last band is open, its upper end is the last key written. */
	private static final Map<String, int[]> BAND_AGES = Map.of(
			"18-29", new int[]{18, 29}, "30-39", new int[]{30, 39}, "40-49", new int[]{40, 49}, "50-59", new int[]{50, 59},
			"60-69", new int[]{60, 69}, "70-79", new int[]{70, 79}, "80+", new int[]{80, 80});

	private record Entry(double percent, String sourceSeason) {
	}

	/** Season, then band. */
	private final Map<String, Map<String, Entry>> coverage;

	private VaccinationCoverage(Map<String, Map<String, Entry>> coverage) {
		this.coverage = coverage;
	}

	/**
	 * Reads the file: tab separated, lines starting with {@code #} and blank lines are skipped, the first other line is the header
	 * (columns {@code season}, {@code band}, {@code coverage_band}, {@code source_season}; others are ignored).
	 *
	 * @throws IllegalArgumentException if the file has no header or no rows, a column is missing, a band is not one of
	 *                                  {@link #BANDS}, a row is repeated, a coverage is not between 0 and 100, or a season lacks a band
	 */
	public static VaccinationCoverage read(Path file) throws IOException {

		Map<String, Map<String, Entry>> seasons = new LinkedHashMap<>();
		String[] header = null;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#"))
				continue;
			String[] cols = line.split("\t", -1);
			if (header == null) {
				header = cols;
				continue;
			}
			String season = cols[column(header, "season", file)];
			String band = cols[column(header, "band", file)];
			double percent = Double.parseDouble(cols[column(header, "coverage_band", file)]);
			String source = cols[column(header, "source_season", file)];

			if (!BANDS.contains(band))
				throw new IllegalArgumentException("Unknown age band '" + band + "' in " + file);
			if (!(percent >= 0.0 && percent <= 100.0))
				throw new IllegalArgumentException("The coverage " + percent + " of " + season + ", band " + band + " in " + file
						+ " is not between 0 and 100");
			if (seasons.computeIfAbsent(season, s -> new LinkedHashMap<>()).put(band, new Entry(percent, source)) != null)
				throw new IllegalArgumentException("Season " + season + ", band " + band + " is given twice in " + file);
		}
		if (header == null || seasons.isEmpty())
			throw new IllegalArgumentException("No rows in " + file);

		for (Map.Entry<String, Map<String, Entry>> season : seasons.entrySet())
			for (String band : BANDS)
				if (!season.getValue().containsKey(band))
					throw new IllegalArgumentException("Season " + season.getKey() + " has no band " + band + " in " + file);

		return new VaccinationCoverage(seasons);
	}

	/** The seasons of the file, in the order of the file. */
	public List<String> seasons() {
		return new ArrayList<>(coverage.keySet());
	}

	/**
	 * The share of the persons of a band who are vaccinated in a season.
	 *
	 * @return between 0 and 1
	 * @throws IllegalArgumentException for a season or a band that the file does not have
	 */
	public double coverage(String season, String band) {
		// the file has two decimals of a percent; rounding keeps floating point noise out of the written config
		return Math.round(entry(season, band).percent() * 100.0) / 10000.0;
	}

	/** The season whose data a season carries: the season itself, or an earlier one (A42). */
	public String sourceSeason(String season) {
		return entry(season, BANDS.get(0)).sourceSeason();
	}

	/**
	 * The share by age for {@code PriorImmunityConfigGroup} and {@code SeasonalVaccinationConfigGroup}: the share of each band is held
	 * over its ages, zero below {@code minAge}, and the last band (80 and over) holds its value beyond the last key. A band is
	 * written as the keys of its first and last age; the ages are whole years, so the interpolation between two keys
	 * does not blur the step between two bands.
	 *
	 * @param minAge 60 for the persons aged 60 and over (V1a), 18 to include the bands below 60 (V1b); the first band that is
	 *               included starts at this age
	 * @throws IllegalArgumentException if {@code minAge} is not the first age of a band
	 */
	public Map<Integer, Double> shareByAge(String season, int minAge) {

		Map<Integer, Double> shares = new TreeMap<>();
		boolean found = false;
		for (String band : BANDS) {
			int[] ages = BAND_AGES.get(band);
			if (ages[0] == minAge)
				found = true;
			if (!found)
				continue;
			double share = coverage(season, band);
			shares.put(ages[0], share);
			shares.put(ages[1], share);
		}
		if (!found)
			throw new IllegalArgumentException("Age " + minAge + " is not the first age of a band " + BANDS);

		shares.put(0, 0.0);
		if (minAge > 1)
			shares.put(minAge - 1, 0.0);
		return shares;
	}

	private Entry entry(String season, String band) {
		Map<String, Entry> bands = coverage.get(season);
		if (bands == null)
			throw new IllegalArgumentException("No coverage for season " + season + "; the file has " + coverage.keySet());
		Entry entry = bands.get(band);
		if (entry == null)
			throw new IllegalArgumentException("No coverage for band " + band + " in season " + season);
		return entry;
	}

	private static int column(String[] header, String name, Path file) {
		for (int i = 0; i < header.length; i++)
			if (header[i].equals(name))
				return i;
		throw new IllegalArgumentException("No column '" + name + "' in " + file);
	}
}
