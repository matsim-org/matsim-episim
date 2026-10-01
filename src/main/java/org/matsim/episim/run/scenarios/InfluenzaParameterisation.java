package org.matsim.episim.run.scenarios;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimPerson;
import org.matsim.episim.EpisimUtils;
import org.matsim.episim.PathogenConfigGroup;
import org.matsim.episim.TracingConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.model.Pathogen;
import org.matsim.episim.model.Transition;
import org.matsim.episim.model.TransmissionWeights;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.policy.FixedPolicy;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.matsim.episim.model.Transition.to;

/**
 * City-independent part of the influenza scenarios (docs/influenza-parameterisation.md) and helpers for the city
 * generators.
 */
public final class InfluenzaParameterisation {

	/** Monday of KW 39/2025, one week before the RKI season (KW 40 to KW 20). */
	public static final LocalDate SEASON_START = LocalDate.parse("2025-09-22");

	/** Sunday of KW 20/2026. */
	public static final LocalDate SEASON_END = LocalDate.parse("2026-05-17");

	public static final Pathogen INFLUENZA = new Pathogen("influenza");
	public static final VirusStrain INFLUENZA_STRAIN = VirusStrain.of(INFLUENZA, "influenza");

	/** Closed during school holidays; kindergartens and universities stay open. */
	public static final String[] SCHOOLS = {"educ_primary", "educ_secondary", "educ_tertiary", "educ_other"};

	/**
	 * Infectiousness of the pooled strain of the single-season scenarios: a calibration placeholder, which a batch replaces
	 * by the values it runs.
	 */
	public static final double SINGLE_SEASON_INFECTIOUSNESS = 1.0;

	/**
	 * Age susceptibility of A(H3N2), the pooled strain of the single-season scenarios: 12-18 y hazard ratio 2.04 against
	 * adults (Sauter 2026, doi:10.1038/s41467-026-76037-x); the other ages are not raised.
	 */
	public static final Map<Integer, Double> AGE_SUSCEPTIBILITY_H3N2 = Map.of(0, 1.0, 11, 1.0, 12, 2.04, 18, 2.04, 19, 1.0);

	/**
	 * A strain of a run: what distinguishes it from the other strains of the pathogen.
	 *
	 * @param strain            the strain, a member of {@link #INFLUENZA}
	 * @param ageSusceptibility relative susceptibility by age (key: age, linear between the keys, the last value from the
	 *                          last key on)
	 * @param infectiousness    the infectiousness of the strain
	 */
	public record StrainSpec(VirusStrain strain, Map<Integer, Double> ageSusceptibility, double infectiousness) {
	}

	/** Residual fraction the COVID Cologne scenario used for closed schools, an assumption for holidays. */
	public static final double HOLIDAY_SCHOOL_FRACTION = 0.2;

	/**
	 * Length of {@code recovered -> susceptible} in the single-season scenarios: protection halves only 3.5-7 y after
	 * an infection (Ranjeva 2019, doi:10.1038/s41467-019-09652-6), so there is no reinfection within a season.
	 */
	public static final int SINGLE_SEASON_REFRACTORY_DAYS = 365;

	/** ICU stay median 4 d, IQR 1-8 d (doi:10.3390/v17111467). */
	private static final double ICU_LOS_SIGMA = Math.log(8.0 / 1.0) / (2 * 0.6745);

	// outdoor fraction as in the VSP production scenarios (WeatherModel.midpoints_185_250)
	private static final double RAIN_THRESHOLD = 0.5;
	private static final double T_MID = 18.5;
	private static final double T_MID_FALL_2020 = 25.0;
	private static final double T_RANGE = 5.0;

	private InfluenzaParameterisation() {
	}

	/**
	 * Adds influenza to a city's base config for the single season {@link #SEASON_START} to {@link #SEASON_END} and replaces
	 * any other import; outdoor fraction and policy are left to the caller.
	 */
	public static void configure(Config config, Map<LocalDate, Integer> importSchedule) {
		configure(config, importSchedule, SEASON_START, SINGLE_SEASON_REFRACTORY_DAYS);
	}

	/**
	 * Adds influenza to a city's base config and replaces any other import; outdoor fraction and policy are left to the
	 * caller.
	 *
	 * @param startDate      first day of the run, also the date of the zero SARS-CoV-2 import entry
	 * @param refractoryDays days an agent stays in {@code recovered} before it is susceptible again
	 */
	public static void configure(Config config, Map<LocalDate, Integer> importSchedule, LocalDate startDate, int refractoryDays) {
		// one pooled strain, A(H3N2)-dominated (ARE-Wochenbericht KW 44/2022, doi:10.25646/10757)
		configure(config, List.of(new StrainSpec(INFLUENZA_STRAIN, AGE_SUSCEPTIBILITY_H3N2, SINGLE_SEASON_INFECTIOUSNESS)),
				Map.of(INFLUENZA_STRAIN, importSchedule), startDate, refractoryDays);
	}

	/**
	 * Adds influenza with several strains to a city's base config and replaces any other import; outdoor fraction and
	 * policy are left to the caller. The pathogen, its natural history and its transmission are the same for all strains.
	 *
	 * @param strains         the strains, all of pathogen {@link #INFLUENZA}; the order is the order in the written config
	 * @param importSchedules the import of every strain; a strain without one would be seeded with one infection a day
	 * @param startDate       first day of the run, also the date of the zero SARS-CoV-2 import entry
	 * @param refractoryDays  days an agent stays in {@code recovered} before it is susceptible again
	 */
	public static void configure(Config config, List<StrainSpec> strains, Map<VirusStrain, Map<LocalDate, Integer>> importSchedules,
								 LocalDate startDate, int refractoryDays) {

		for (StrainSpec spec : strains) {
			if (!INFLUENZA.equals(spec.strain().getPathogen()))
				throw new IllegalArgumentException("Strain " + spec.strain() + " is not a strain of " + INFLUENZA.getName());
			if (!importSchedules.containsKey(spec.strain()))
				throw new IllegalArgumentException("Strain " + spec.strain() + " has no import schedule; without one it would be "
						+ "seeded with one infection a day");
		}

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		VirusStrainConfigGroup virusStrainConfig = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);
		PathogenConfigGroup pathogenConfig = ConfigUtils.addOrGetModule(config, PathogenConfigGroup.class);

		episimConfig.setStartDate(startDate);

		for (StrainSpec spec : strains) {
			VirusStrainConfigGroup.StrainParams strain = virusStrainConfig.getOrAddParams(spec.strain());
			strain.setPathogen(INFLUENZA);
			strain.setInfectiousness(spec.infectiousness());
			strain.setFactorSeriouslySick(1.0);
			strain.setFactorCritical(1.0);
			strain.setAgeSusceptibility(spec.ageSusceptibility());
			// no age effect (Cauchemez 2009, doi:10.1056/NEJMoa0905498)
			strain.setAgeInfectivity(Map.of(0, 1.0));
		}

		// seriouslySick is divided by hospitalFactor, which the model multiplies back in
		double hospitalFactor = episimConfig.getHospitalFactor();
		PathogenConfigGroup.PathogenParams influenza = pathogenConfig.getOrAddParams(INFLUENZA);
		PathogenConfigGroup.ProgressionParams byAge = influenza.getOrAddProgressionParams(true);
		// 268 of 478 infections symptomatic (Cohen 2021, doi:10.1016/S2214-109X(21)00141-8)
		byAge.setShowingSymptomsProbabilityByAge(Map.of(0, 0.56));
		// hospitalisations per symptomatic illness, CDC burden 2018-19
		byAge.setSeriouslySickProbabilityByAge(Map.of(
				0, 0.00697 / hospitalFactor,
				5, 0.00274 / hospitalFactor,
				18, 0.00561 / hospitalFactor,
				50, 0.01060 / hospitalFactor,
				65, 0.09091 / hospitalFactor));
		// ICU share, Germany 2022/23 (Meyer 2026, doi:10.1007/s40121-026-01384-7)
		byAge.setCriticalProbabilityByAge(Map.of(0, 0.044, 18, 0.099, 60, 0.113));
		// ICU mortality (Suarez-Sanchez 2025, doi:10.1111/irv.70073)
		byAge.setDeathProbabilityByAge(Map.of(0, 0.24));
		// aerosols ~ half of household transmission (Cowling 2013, doi:10.1038/ncomms2922)
		influenza.setRouteTransmissibility(TransmissionWeights.parse("respiratory=1.0"));
		// illness cuts R to ~1/4 (Van Kerckhove 2013, doi:10.1093/aje/kwt196); low confidence
		influenza.setSymptomaticIsolationProbabilityByAge(Map.of(0, 0.75));
		influenza.setSymptomaticIsolationStatus(EpisimPerson.QuarantineStatus.atHome);
		// placeholder: the former built-in COVID curve (arXiv:2007.06602)
		influenza.setInfectivityProfile(sampledNormal(0.5, 2.6, -8, 12));

		episimConfig.setProgressionConfig(progressionConfig(Transition.config(), refractoryDays).build());

		ConfigUtils.addOrGetModule(config, TracingConfigGroup.class).setPutTraceablePersonsInQuarantineAfterDay(Integer.MAX_VALUE);

		episimConfig.getInfections_pers_per_day().clear();
		for (StrainSpec spec : strains)
			episimConfig.setInfections_pers_per_day(spec.strain(), importSchedules.get(spec.strain()));
		// explicit, otherwise an empty map falls back to 1 SARS-CoV-2 infection per day
		episimConfig.setInfections_pers_per_day(VirusStrain.SARS_CoV_2, Map.of(startDate, 0));

		// no antibody parameters: influenza induces no antibodies, so the antibody severity factor stays 1
	}

	/**
	 * Daily import in agents over the season, proportional to the centred three-week mean of the weekly notifications;
	 * fractions are carried over, so the season total matches.
	 */
	public static Map<LocalDate, Integer> importSchedule(NavigableMap<LocalDate, Integer> weeklyCases, double perWeeklyCase,
														 String source) {
		return importSchedule(weeklyCases, perWeeklyCase, source, SEASON_START, SEASON_END);
	}

	/**
	 * As {@link #importSchedule(NavigableMap, double, String)} for the days from {@code start} to {@code end}, both included.
	 */
	public static Map<LocalDate, Integer> importSchedule(NavigableMap<LocalDate, Integer> weeklyCases, double perWeeklyCase,
														 String source, LocalDate start, LocalDate end) {
		Map<LocalDate, Integer> schedule = new TreeMap<>();
		double expected = 0;
		long issued = 0;
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			LocalDate week = day.with(DayOfWeek.MONDAY);
			Integer before = weeklyCases.get(week.minusWeeks(1));
			Integer current = weeklyCases.get(week);
			Integer after = weeklyCases.get(week.plusWeeks(1));
			if (before == null || current == null || after == null)
				throw new IllegalStateException(source + " lacks the week of " + week + " or a neighbour of it.");

			expected += perWeeklyCase * (before + current + after) / 3.0;
			long agents = Math.round(expected) - issued;
			issued += agents;
			schedule.put(day, (int) agents);
		}
		return schedule;
	}

	/** Reads {@code week<TAB>cases} rows, keyed by the Monday of the ISO week. */
	public static NavigableMap<LocalDate, Integer> readWeeklyCases(Path file) throws IOException {
		NavigableMap<LocalDate, Integer> cases = new TreeMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#") || line.startsWith("week"))
				continue;
			String[] cols = line.split("\t");
			cases.put(LocalDate.parse(cols[0].trim() + "-1", DateTimeFormatter.ISO_WEEK_DATE), Integer.parseInt(cols[1].trim()));
		}
		return cases;
	}

	public static Map<LocalDate, Double> weatherOutdoorFraction(String weatherPath, String avgWeatherPath) {
		try {
			return EpisimUtils.getOutDoorFractionFromDateAndTemp2(new File(weatherPath), new File(avgWeatherPath),
					RAIN_THRESHOLD, T_MID, T_MID_FALL_2020, T_MID, T_MID, T_RANGE, 1.0, 1.0);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * {@link #weatherOutdoorFraction(String, String)} for the days from {@code from} to {@code to}, both included; the
	 * helper itself continues with the average year for three years after the last day of the weather file.
	 */
	public static Map<LocalDate, Double> weatherOutdoorFraction(String weatherPath, String avgWeatherPath, LocalDate from, LocalDate to) {
		Map<LocalDate, Double> days = new TreeMap<>();
		weatherOutdoorFraction(weatherPath, avgWeatherPath).forEach((date, fraction) -> {
			if (!date.isBefore(from) && !date.isAfter(to))
				days.put(date, fraction);
		});
		return days;
	}

	public static void closeSchools(FixedPolicy.ConfigBuilder policy, String firstHoliday, String lastHoliday) {
		closeSchools(policy, firstHoliday, lastHoliday, HOLIDAY_SCHOOL_FRACTION);
	}

	/** As {@link #closeSchools(FixedPolicy.ConfigBuilder, String, String)} with the given remaining attendance. */
	public static void closeSchools(FixedPolicy.ConfigBuilder policy, String firstHoliday, String lastHoliday, double fraction) {
		policy.restrict(LocalDate.parse(firstHoliday), fraction, SCHOOLS);
		policy.restrict(LocalDate.parse(lastHoliday).plusDays(1), 1.0, SCHOOLS);
	}

	/** The config can reference progression only as a file. */
	public static void writeProgression(EpisimConfigGroup episimConfig, String progressionPath) throws IOException {
		writeProgression(episimConfig, Path.of(""), progressionPath);
	}

	/**
	 * As {@link #writeProgression(EpisimConfigGroup, String)}, with the file below {@code outputRoot}. The config
	 * references the file by the path resolved against {@code outputRoot}, which is {@code progressionPath} itself for the
	 * empty root.
	 */
	public static void writeProgression(EpisimConfigGroup episimConfig, Path outputRoot, String progressionPath) throws IOException {
		File progressionFile = outputRoot.resolve(progressionPath).toFile();
		writeHocon(episimConfig.getProgressionConfig(), progressionFile);
		episimConfig.setProgressionConfig(ConfigFactory.parseFile(progressionFile));
	}

	/** The config can reference the policy only as a file. */
	public static void writePolicy(EpisimConfigGroup episimConfig, FixedPolicy.ConfigBuilder policy, String policyPath)
			throws IOException {
		writePolicy(episimConfig, policy, Path.of(""), policyPath);
	}

	/** As {@link #writePolicy(EpisimConfigGroup, FixedPolicy.ConfigBuilder, String)}, see {@link #writeProgression(EpisimConfigGroup, Path, String)}. */
	public static void writePolicy(EpisimConfigGroup episimConfig, FixedPolicy.ConfigBuilder policy, Path outputRoot, String policyPath)
			throws IOException {
		String policyFile = outputRoot.resolve(policyPath).toString();
		writeHocon(policy.build(), new File(policyFile));
		episimConfig.setPolicyConfig(policyFile);
	}

	private static void writeHocon(com.typesafe.config.Config config, File file) throws IOException {
		if (file.getParentFile() != null)
			Files.createDirectories(file.getParentFile().toPath());
		String rendered = config.root().render(ConfigRenderOptions.defaults().setOriginComments(false).setJson(false));
		try (FileWriter writer = new FileWriter(file)) {
			writer.write(rendered);
		}
	}

	/** Normal density on whole days, peak 1, four decimals. */
	static Map<Integer, Double> sampledNormal(double mean, double sd, int from, int to) {
		Map<Integer, Double> profile = new TreeMap<>();
		for (int day = from; day <= to; day++) {
			double z = (day - mean) / sd;
			profile.put(day, Math.round(Math.exp(-0.5 * z * z) * 1e4) / 1e4);
		}
		return profile;
	}

	/** The progression of the single-season scenarios, see {@link #SINGLE_SEASON_REFRACTORY_DAYS}. */
	static Transition.Builder progressionConfig(Transition.Builder builder) {
		return progressionConfig(builder, SINGLE_SEASON_REFRACTORY_DAYS);
	}

	/**
	 * @param refractoryDays days in {@code recovered} before an agent is susceptible again
	 */
	static Transition.Builder progressionConfig(Transition.Builder builder, int refractoryDays) {

		return builder
				// latent period 0.5-1 d (Carrat 2008, doi:10.1093/aje/kwm375)
				.from(EpisimPerson.DiseaseStatus.infectedButNotContagious,
						to(EpisimPerson.DiseaseStatus.contagious, Transition.fixed(1)))

				// incubation median 1.4 d (Lessler 2009, doi:10.1016/S1473-3099(09)70069-6);
				// asymptomatic shedding 3-5 d (RKI AGI 2018/19, Ip 2017, doi:10.1093/cid/ciw841)
				.from(EpisimPerson.DiseaseStatus.contagious,
						to(EpisimPerson.DiseaseStatus.showingSymptoms, Transition.logNormalWithMedianAndSigma(1.0, Math.log(1.51))),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(4.0, 2.0)))

				// onset -> admission median 3 d (FluSurv-NET, doi:10.1093/ofid/ofad599);
				// illness ends by day 6-7 (Ip 2016, doi:10.1093/cid/civ909)
				.from(EpisimPerson.DiseaseStatus.showingSymptoms,
						to(EpisimPerson.DiseaseStatus.seriouslySick, Transition.logNormalWithMedianAndSigma(3.0, 0.6)),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(6.0, 2.0)))

				// ward -> ICU: COVID placeholder; stay mean 5.6 d (Meyer 2026, doi:10.1007/s40121-026-01384-7)
				.from(EpisimPerson.DiseaseStatus.seriouslySick,
						to(EpisimPerson.DiseaseStatus.critical, Transition.logNormalWithMedianAndStd(1.0, 1.0)),
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMeanAndStd(5.6, 6.0)))

				// ICU stay; time to death assumed the same
				.from(EpisimPerson.DiseaseStatus.critical,
						to(EpisimPerson.DiseaseStatus.seriouslySickAfterCritical, Transition.logNormalWithMedianAndSigma(4.0, ICU_LOS_SIGMA)),
						to(EpisimPerson.DiseaseStatus.deceased, Transition.logNormalWithMedianAndSigma(4.0, ICU_LOS_SIGMA)))

				// COVID placeholder
				.from(EpisimPerson.DiseaseStatus.seriouslySickAfterCritical,
						to(EpisimPerson.DiseaseStatus.recovered, Transition.logNormalWithMedianAndStd(7.0, 7.0)))

				// 365 d in the single-season scenarios: protection halves after 3.5-7 y (Ranjeva 2019,
				// doi:10.1038/s41467-019-09652-6), so no reinfection in a season
				.from(EpisimPerson.DiseaseStatus.recovered,
						to(EpisimPerson.DiseaseStatus.susceptible, Transition.fixed(refractoryDays)));
	}

}
