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

	/** Residual fraction the COVID Cologne scenario used for closed schools, an assumption for holidays. */
	public static final double HOLIDAY_SCHOOL_FRACTION = 0.2;

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
	 * Adds influenza to a city's base config and replaces any other import; outdoor fraction and policy are left to the
	 * caller.
	 */
	public static void configure(Config config, Map<LocalDate, Integer> importSchedule) {

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		VirusStrainConfigGroup virusStrainConfig = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);
		PathogenConfigGroup pathogenConfig = ConfigUtils.addOrGetModule(config, PathogenConfigGroup.class);

		episimConfig.setStartDate(SEASON_START);

		// one pooled strain, A(H3N2)-dominated (ARE-Wochenbericht KW 44/2022, doi:10.25646/10757)
		VirusStrainConfigGroup.StrainParams strain = virusStrainConfig.getOrAddParams(INFLUENZA_STRAIN);
		strain.setPathogen(INFLUENZA);
		// calibration placeholder
		strain.setInfectiousness(1.0);
		strain.setFactorSeriouslySick(1.0);
		strain.setFactorCritical(1.0);
		// 12-18 y HR 2.04 vs adults (Sauter 2026, doi:10.1038/s41467-026-76037-x)
		strain.setAgeSusceptibility(Map.of(0, 1.0, 11, 1.0, 12, 2.04, 18, 2.04, 19, 1.0));
		// no age effect (Cauchemez 2009, doi:10.1056/NEJMoa0905498)
		strain.setAgeInfectivity(Map.of(0, 1.0));

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

		episimConfig.setProgressionConfig(progressionConfig(Transition.config()).build());

		ConfigUtils.addOrGetModule(config, TracingConfigGroup.class).setPutTraceablePersonsInQuarantineAfterDay(Integer.MAX_VALUE);

		episimConfig.getInfections_pers_per_day().clear();
		episimConfig.setInfections_pers_per_day(INFLUENZA_STRAIN, importSchedule);
		// explicit, otherwise an empty map falls back to 1 SARS-CoV-2 infection per day
		episimConfig.setInfections_pers_per_day(VirusStrain.SARS_CoV_2, Map.of(SEASON_START, 0));

		// no antibody parameters: influenza induces no antibodies, so the antibody severity factor stays 1
	}

	/**
	 * Daily import in agents over the season, proportional to the centred three-week mean of the weekly notifications;
	 * fractions are carried over, so the season total matches.
	 */
	public static Map<LocalDate, Integer> importSchedule(NavigableMap<LocalDate, Integer> weeklyCases, double perWeeklyCase,
														 String source) {
		Map<LocalDate, Integer> schedule = new TreeMap<>();
		double expected = 0;
		long issued = 0;
		for (LocalDate day = SEASON_START; !day.isAfter(SEASON_END); day = day.plusDays(1)) {
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

	public static void closeSchools(FixedPolicy.ConfigBuilder policy, String firstHoliday, String lastHoliday) {
		policy.restrict(LocalDate.parse(firstHoliday), HOLIDAY_SCHOOL_FRACTION, SCHOOLS);
		policy.restrict(LocalDate.parse(lastHoliday).plusDays(1), 1.0, SCHOOLS);
	}

	/** The config can reference progression only as a file. */
	public static void writeProgression(EpisimConfigGroup episimConfig, String progressionPath) throws IOException {
		File progressionFile = new File(progressionPath);
		writeHocon(episimConfig.getProgressionConfig(), progressionFile);
		episimConfig.setProgressionConfig(ConfigFactory.parseFile(progressionFile));
	}

	/** The config can reference the policy only as a file. */
	public static void writePolicy(EpisimConfigGroup episimConfig, FixedPolicy.ConfigBuilder policy, String policyPath)
			throws IOException {
		writeHocon(policy.build(), new File(policyPath));
		episimConfig.setPolicyConfig(policyPath);
	}

	private static void writeHocon(com.typesafe.config.Config config, File file) throws IOException {
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

	static Transition.Builder progressionConfig(Transition.Builder builder) {

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

				// protection halves after 3.5-7 y (Ranjeva 2019, doi:10.1038/s41467-019-09652-6): no reinfection in a season
				.from(EpisimPerson.DiseaseStatus.recovered,
						to(EpisimPerson.DiseaseStatus.susceptible, Transition.fixed(365)));
	}

}
