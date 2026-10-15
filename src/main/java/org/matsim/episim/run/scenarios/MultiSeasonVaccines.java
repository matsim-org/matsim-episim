package org.matsim.episim.run.scenarios;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimPerson.DiseaseStatus;
import org.matsim.episim.ImmunityConfigGroup;
import org.matsim.episim.ProtectionCurve;
import org.matsim.episim.SeasonalVaccinationConfigGroup;
import org.matsim.episim.VaccinationConfigGroup;
import org.matsim.episim.model.VaccinationType;
import org.matsim.episim.model.VirusStrain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The yearly influenza vaccination of the multi-season scenarios (docs/influenza-multiseasonal.md, section 7.6; decision D19;
 * assumptions A41 to A48): one product per season in the {@code immunity} group, the neutral entry in the
 * {@code vaccination} group, and the campaigns in the {@code seasonalVaccination} group.
 *
 * <p>The protection of a product comes from the effectiveness (VE) of the season and the strain (section 7.1 of the decision log,
 * placeholders where there is none). The VE is split as {@code VE = 1 - (1 - pInf)(1 - pSym)}, with {@code pInf = k * VE}
 * against infection and {@code pSym} against showing symptoms given infection (A46). Protection against {@code seriouslySick} and
 * {@code critical} is zero. After the vaccination the protection rises to the full VE in {@code rampDays}, declines to
 * {@code halfFraction} of it on day {@code halfDays} and is zero from day {@code endDays} (A47).</p>
 */
public final class MultiSeasonVaccines {

	/** The product of the vaccination of one season. */
	public static final VaccinationType FLU_2022_23 = VaccinationType.of("flu_2022_23");
	public static final VaccinationType FLU_2023_24 = VaccinationType.of("flu_2023_24");
	public static final VaccinationType FLU_2024_25 = VaccinationType.of("flu_2024_25");
	public static final VaccinationType FLU_2025_26 = VaccinationType.of("flu_2025_26");

	/** The seasons of the scenario, in order. */
	public static final List<String> SEASONS = List.of("2022/23", "2023/24", "2024/25", "2025/26");

	/** Age from which the vaccination is given in version V1a (the persons aged 60 and over), and in V1b (all adults). */
	private static final int MIN_AGE_STANDARD = 60;
	private static final int MIN_AGE_RISK_GROUP = 18;

	/** Effectiveness of the product of a season against the strains of that season (A45). */
	private static final Map<VaccinationType, Map<VirusStrain, Double>> EFFECTIVENESS = Map.of(
			FLU_2022_23, Map.of(MultiSeasonStrains.H3N2_2022_23, 0.36, MultiSeasonStrains.BVIC, 0.76),
			FLU_2023_24, Map.of(MultiSeasonStrains.H1N1PDM09_2023_24, 0.53, MultiSeasonStrains.BVIC, 0.58),
			FLU_2024_25, Map.of(MultiSeasonStrains.H1N1PDM09_2024_25, 0.43, MultiSeasonStrains.BVIC, 0.58),
			// 0.30 (A(H3N2), interim 2023/24) times 0.4 (the drift factor of subclade K, A5)
			FLU_2025_26, Map.of(MultiSeasonStrains.H3N2K_2025_26, 0.12));

	/** The shape of the campaign inside the months October to December (A44). */
	public enum Timing {

		/** 50 % of the vaccinations by 31 October, 85 % by 30 November, 100 % by 31 December, uniform within each month. */
		PROFILE,

		/** Uniform from 1 October to 31 December. */
		UNIFORM,

		/** As {@link #PROFILE}, four weeks later. */
		LATE
	}

	/**
	 * The parameters of the vaccination; {@link #defaults()} are the values of the decision log, the others the sensitivity cases.
	 *
	 * @param effectScale         factor on all effectiveness values (A45); 0 lets the campaign run and the vaccine do nothing
	 * @param infectionShare      {@code k}, the share of the effectiveness that acts against infection (A46)
	 * @param rampDays            days after the vaccination until the full protection (A47)
	 * @param halfDays            day on which the protection has declined to {@code halfFraction}
	 * @param halfFraction        the share of the protection left on day {@code halfDays}
	 * @param endDays             day on which the protection is zero
	 * @param timing              the shape of the campaign
	 * @param coverageShift2025   points added to the coverage of the season 2025/26, which holds the coverage of 2024/25 (A42)
	 * @param riskGroup           whether the bands 18 to 59 are vaccinated as well (V1b)
	 */
	public record Parameters(double effectScale, double infectionShare, int rampDays, int halfDays, double halfFraction, int endDays,
							 Timing timing, double coverageShift2025, boolean riskGroup) {

		public Parameters {
			if (!(effectScale >= 0))
				throw new IllegalArgumentException("The effectiveness scale has to be 0 or more: " + effectScale);
			if (!(infectionShare >= 0 && infectionShare <= 1) || !(halfFraction >= 0 && halfFraction <= 1))
				throw new IllegalArgumentException("The infection share and the half fraction have to be between 0 and 1: "
						+ infectionShare + ", " + halfFraction);
			if (!(0 < rampDays && rampDays < halfDays && halfDays < endDays))
				throw new IllegalArgumentException("The days have to rise: " + rampDays + ", " + halfDays + ", " + endDays);
			if (timing == null)
				throw new IllegalArgumentException("The timing is missing");
		}

		public static Parameters defaults() {
			return new Parameters(1.0, 0.5, 14, 180, 0.5, 365, Timing.PROFILE, 0.0, false);
		}

		public Parameters withEffectScale(double scale) {
			return new Parameters(scale, infectionShare, rampDays, halfDays, halfFraction, endDays, timing, coverageShift2025, riskGroup);
		}

		public Parameters withInfectionShare(double share) {
			return new Parameters(effectScale, share, rampDays, halfDays, halfFraction, endDays, timing, coverageShift2025, riskGroup);
		}

		public Parameters withTiming(Timing timing) {
			return new Parameters(effectScale, infectionShare, rampDays, halfDays, halfFraction, endDays, timing, coverageShift2025, riskGroup);
		}

		public Parameters withCoverageShift2025(double points) {
			return new Parameters(effectScale, infectionShare, rampDays, halfDays, halfFraction, endDays, timing, points, riskGroup);
		}

		public Parameters withRiskGroup(boolean riskGroup) {
			return new Parameters(effectScale, infectionShare, rampDays, halfDays, halfFraction, endDays, timing, coverageShift2025, riskGroup);
		}
	}

	private MultiSeasonVaccines() {
	}

	/** The product of a season such as {@code 2022/23}. */
	public static VaccinationType product(String season) {
		return VaccinationType.of("flu_" + startYear(season) + "_" + season.substring(5));
	}

	/**
	 * Adds the products to the {@code immunity} group (which {@link MultiSeasonImmunity#configure} has to have set up before: the
	 * explicit model), a neutral entry for each product to the {@code vaccination} group, and the campaigns of all seasons to the
	 * {@code seasonalVaccination} group.
	 *
	 * @param coverage the coverage by age band of the district; the seasons of {@link #SEASONS} have to be in it
	 */
	public static void configure(Config config, Parameters parameters, VaccinationCoverage coverage) {

		ImmunityConfigGroup immunity = ConfigUtils.addOrGetModule(config, ImmunityConfigGroup.class);
		VaccinationConfigGroup vaccination = ConfigUtils.addOrGetModule(config, VaccinationConfigGroup.class);
		SeasonalVaccinationConfigGroup campaigns = ConfigUtils.addOrGetModule(config, SeasonalVaccinationConfigGroup.class);

		VirusStrain[] strains = MultiSeasonStrains.all().stream().map(InfluenzaParameterisation.StrainSpec::strain)
				.toArray(VirusStrain[]::new);
		int minAge = parameters.riskGroup() ? MIN_AGE_RISK_GROUP : MIN_AGE_STANDARD;

		for (String season : SEASONS) {
			VaccinationType product = product(season);

			ImmunityConfigGroup.SourceParams source = immunity.getOrAddSource(product);
			for (VirusStrain target : strains)
				for (DiseaseStatus status : ImmunityConfigGroup.SUPPORTED_TARGETS)
					source.setProtection(status, target, curve(product, target, status, parameters));

			// the infection model reads the vaccination infectivity of every vaccinated infector (A48)
			VaccinationConfigGroup.VaccinationParams neutral = vaccination.getOrAddParams(product);
			neutral.setInfectivity(VaccinationConfigGroup.forStrain(strains).atDay(0, 1.0));
			neutral.setBoostInfectivity(VaccinationConfigGroup.forStrain(strains).atDay(0, 1.0));

			campaigns.addSeason(season, product, shares(coverage, season, minAge, parameters), timing(season, parameters.timing()));
		}
	}

	/**
	 * The share by age of a season: the coverage of the file, and for 2025/26 shifted by {@code coverageShift2025} points (not
	 * below 0 and not above 1) in the ages that are vaccinated.
	 */
	static Map<Integer, Double> shares(VaccinationCoverage coverage, String season, int minAge, Parameters parameters) {

		Map<Integer, Double> shares = new TreeMap<>(coverage.shareByAge(season, minAge));
		if (season.equals(SEASONS.get(SEASONS.size() - 1)) && parameters.coverageShift2025() != 0.0)
			shares.replaceAll((age, share) -> age < minAge ? share
					: Math.round(Math.min(1.0, Math.max(0.0, share + parameters.coverageShift2025() / 100.0)) * 10_000) / 10_000.0);
		return shares;
	}

	/** The cumulative share of the campaign of a season at the end of the days, see {@link SeasonalVaccinationConfigGroup.Season}. */
	static NavigableMap<LocalDate, Double> timing(String season, Timing timing) {

		int year = startYear(season);
		NavigableMap<LocalDate, Double> cumulative = new TreeMap<>();
		cumulative.put(LocalDate.of(year, 9, 30), 0.0);
		if (timing == Timing.UNIFORM) {
			cumulative.put(LocalDate.of(year, 12, 31), 1.0);
			return cumulative;
		}

		int shift = timing == Timing.LATE ? 28 : 0;
		NavigableMap<LocalDate, Double> shifted = new TreeMap<>();
		cumulative.forEach((date, share) -> shifted.put(date.plusDays(shift), share));
		shifted.put(LocalDate.of(year, 10, 31).plusDays(shift), 0.5);
		shifted.put(LocalDate.of(year, 11, 30).plusDays(shift), 0.85);
		shifted.put(LocalDate.of(year, 12, 31).plusDays(shift), 1.0);
		return shifted;
	}

	/**
	 * The protection of a product against {@code status} of a strain: the curve over the days since the vaccination. Zero for the
	 * strains that do not circulate in the season of the product and for the severe statuses (A46).
	 */
	public static ProtectionCurve curve(VaccinationType product, VirusStrain target, DiseaseStatus status, Parameters parameters) {

		Double ve = EFFECTIVENESS.getOrDefault(product, Map.of()).get(target);
		if (ve == null || ve * parameters.effectScale() <= 0.0)
			return ProtectionCurve.NONE;
		if (status != DiseaseStatus.infectedButNotContagious && status != DiseaseStatus.showingSymptoms)
			return ProtectionCurve.NONE;

		int[] days = {0, parameters.rampDays(), parameters.halfDays(), parameters.endDays()};
		double[] factors = {0.0, 1.0, parameters.halfFraction(), 0.0};
		double[] points = new double[days.length * 2];
		for (int i = 0; i < days.length; i++) {
			double effect = Math.min(0.999, ve * parameters.effectScale() * factors[i]);
			double infection = parameters.infectionShare() * effect;
			double protection = status == DiseaseStatus.infectedButNotContagious ? infection : 1.0 - (1.0 - effect) / (1.0 - infection);
			points[2 * i] = days[i];
			points[2 * i + 1] = Math.round(protection * 10_000) / 10_000.0;
		}
		return ProtectionCurve.of(points);
	}

	/** The first year of a season such as {@code 2022/23}. */
	private static int startYear(String season) {
		if (season.length() != 7 || season.charAt(4) != '/')
			throw new IllegalArgumentException("A season is written like 2022/23: " + season);
		return Integer.parseInt(season.substring(0, 4));
	}
}
