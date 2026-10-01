package org.matsim.episim.run.scenarios;

import org.matsim.episim.EpisimUtils;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation.StrainSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The strains of the multi-season influenza scenario, 2022/23 to 2025/26 (docs/influenza-multiseasonal.md, sections 2.2 and
 * 2.4; decisions D1, D9, D12, D13). A strain is an antigenic cluster within a season; all belong to the pathogen
 * {@link InfluenzaParameterisation#INFLUENZA}. The names appear in snapshots, event files and config attributes, so they
 * contain no slash or space.
 */
public final class MultiSeasonStrains {

	/**
	 * Infectiousness of every strain (D13): the value that already gave a fairly good fit of 2025/26 in the single-season
	 * scenario. The run is calibrated through one common factor on the calibration parameter, not per strain.
	 */
	public static final double INFECTIOUSNESS = 0.30;

	/** A(H3N2) 3C.2a1b.2a.2b, the wave of 2022/23. */
	public static final VirusStrain H3N2_2022_23 = strain("H3N2_2022_23");

	/** A(H1N1)pdm09 of 2023/24. */
	public static final VirusStrain H1N1PDM09_2023_24 = strain("H1N1pdm09_2023_24");

	/** A(H1N1)pdm09 of 2024/25, the same subtype as 2023/24. */
	public static final VirusStrain H1N1PDM09_2024_25 = strain("H1N1pdm09_2024_25");

	/** B/Victoria, one strain for 2022/23 to 2024/25, so that its immunity carries forward (D9). */
	public static final VirusStrain BVIC = strain("BVic");

	/** A(H3N2) subclade K (2a.3a.1) of 2025/26. */
	public static final VirusStrain H3N2K_2025_26 = strain("H3N2K_2025_26");

	/**
	 * Age susceptibility of A(H1N1)pdm09: hazard ratio 2.69 (1.33 to 5.46) under 5 years against age 40 and over; 5 to 18
	 * years not significantly raised, so 1.0 (Sauter 2026, doi:10.1038/s41467-026-76037-x, decision D12, assumption A35).
	 */
	public static final Map<Integer, Double> AGE_SUSCEPTIBILITY_H1N1PDM09 = Map.of(0, 2.69, 4, 2.69, 5, 1.0);

	/**
	 * Age susceptibility of B/Victoria: hazard ratio 5.65 (2.85 to 11.21) under 5 years, 5.66 (2.98 to 10.73) at 5 to 11 and
	 * 2.17 (1.07 to 4.42) at 12 to 18 years against age 40 and over; 19 to 40 not reported, so 1.0 (Sauter 2026, D12, A35).
	 */
	public static final Map<Integer, Double> AGE_SUSCEPTIBILITY_BVIC =
			Map.of(0, 5.65, 4, 5.65, 5, 5.66, 11, 5.66, 12, 2.17, 18, 2.17, 19, 1.0);

	private MultiSeasonStrains() {
	}

	/** The strains in the order of the seasons; B/Victoria stands where it first circulates in the modelled seasons. */
	public static List<StrainSpec> all() {
		return List.of(
				new StrainSpec(H3N2_2022_23, InfluenzaParameterisation.AGE_SUSCEPTIBILITY_H3N2, INFECTIOUSNESS),
				new StrainSpec(BVIC, AGE_SUSCEPTIBILITY_BVIC, INFECTIOUSNESS),
				new StrainSpec(H1N1PDM09_2023_24, AGE_SUSCEPTIBILITY_H1N1PDM09, INFECTIOUSNESS),
				new StrainSpec(H1N1PDM09_2024_25, AGE_SUSCEPTIBILITY_H1N1PDM09, INFECTIOUSNESS),
				new StrainSpec(H3N2K_2025_26, InfluenzaParameterisation.AGE_SUSCEPTIBILITY_H3N2, INFECTIOUSNESS));
	}

	/**
	 * The number of agents per age (index = age in years) from a file with the columns {@code age} and {@code agents}; comment
	 * lines start with {@code #}, the separator is a tab. Ages without a row count zero.
	 */
	public static double[] readAgeCounts(Path file) throws IOException {
		Map<Integer, Double> counts = new TreeMap<>();
		boolean header = true;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#"))
				continue;
			if (header) {
				if (!line.equals("age\tagents"))
					throw new IllegalArgumentException("Expected the header 'age<TAB>agents' in " + file + ", got '" + line + "'");
				header = false;
				continue;
			}
			String[] cols = line.split("\t", -1);
			counts.put(Integer.parseInt(cols[0].strip()), Double.parseDouble(cols[1].strip()));
		}
		if (counts.isEmpty())
			throw new IllegalArgumentException("No ages in " + file);
		double[] byAge = new double[counts.keySet().stream().mapToInt(Integer::intValue).max().getAsInt() + 1];
		counts.forEach((age, n) -> byAge[age] = n);
		return byAge;
	}

	/** Mean of an age table over a population: the table interpolated at every age, weighted by the agents of that age. */
	public static double meanSusceptibility(Map<Integer, Double> ageTable, double[] ageCounts) {
		NavigableMap<Integer, Double> table = new TreeMap<>(ageTable);
		double sum = 0, agents = 0;
		for (int age = 0; age < ageCounts.length; age++) {
			sum += ageCounts[age] * EpisimUtils.interpolateEntry(table, age);
			agents += ageCounts[age];
		}
		return sum / agents;
	}

	/**
	 * The strains with their age tables scaled to one mean susceptibility over the population (decision D16). The reference is
	 * the mean of the table of A(H3N2), so the two A(H3N2) strains keep their table, which is the one the infectiousness of 0.30
	 * was found for; each other table is multiplied by the same factor in every age, which keeps its pattern across the ages and
	 * moves the mean to the reference. The hazard ratios of Sauter 2026 are relative to adults within a subtype, so with the
	 * adults at 1.0 in every strain the strains would differ in mean susceptibility (trial run 1: 1.06 for A(H3N2), 1.52 for
	 * B/Victoria). Values are rounded to four decimals.
	 *
	 * @param ageCounts agents per age, see {@link #readAgeCounts(Path)}
	 */
	public static List<StrainSpec> normalised(List<StrainSpec> strains, double[] ageCounts) {
		double reference = meanSusceptibility(InfluenzaParameterisation.AGE_SUSCEPTIBILITY_H3N2, ageCounts);
		List<StrainSpec> scaled = new ArrayList<>();
		for (StrainSpec spec : strains) {
			double factor = reference / meanSusceptibility(spec.ageSusceptibility(), ageCounts);
			if (Math.abs(factor - 1.0) < 1e-12) {
				scaled.add(spec);
				continue;
			}
			Map<Integer, Double> table = new TreeMap<>();
			spec.ageSusceptibility().forEach((age, value) -> table.put(age, Math.round(value * factor * 10_000) / 10_000.0));
			scaled.add(new StrainSpec(spec.strain(), table, spec.infectiousness()));
		}
		return scaled;
	}

	private static VirusStrain strain(String name) {
		return VirusStrain.of(InfluenzaParameterisation.INFLUENZA, name);
	}
}
