package org.matsim.episim.run.scenarios.cologne;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation;
import org.matsim.episim.run.scenarios.MultiSeasonImmunity;
import org.matsim.episim.run.scenarios.MultiSeasonImport;
import org.matsim.episim.run.scenarios.MultiSeasonPolicy;
import org.matsim.episim.run.scenarios.MultiSeasonStrains;
import org.matsim.episim.run.scenarios.MultiSeasonVariant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * Writes the Cologne influenza scenario ({@value #CONFIG_PATH}, {@value #PROGRESSION_PATH}, {@value #POLICY_PATH}):
 * the non-COVID part of the VSP Cologne COVID scenario on the public open-data inputs, plus
 * {@link InfluenzaParameterisation}. Run from the repo root. The scenario is chosen by the first argument (see
 * {@link Scenario}); without an argument it is the single season {@link Scenario#COLOGNE}.
 */
public final class GenerateInfluenzaConfig {

	public static final String CONFIG_PATH = "Scenarios/Cologne/config.xml";
	public static final String PROGRESSION_PATH = "Scenarios/Cologne/progression.conf";
	public static final String POLICY_PATH = "Scenarios/Cologne/policy.conf";

	public static final String IMPORT_DRIVER_PATH = "Scenarios/Cologne/nrw-ifsg-influenza-2025-26.tsv";

	public static final String MULTISEASON_CONFIG_PATH = "Scenarios/Cologne-multiseason/config.xml";
	public static final String MULTISEASON_PROGRESSION_PATH = "Scenarios/Cologne-multiseason/progression.conf";
	public static final String MULTISEASON_POLICY_PATH = "Scenarios/Cologne-multiseason/policy.conf";

	public static final String MULTISEASON_IMPORT_DRIVER_PATH = "Scenarios/Cologne-multiseason/nrw-ifsg-influenza-2022-26.tsv";
	public static final String MULTISEASON_SENTINEL_PATH = "Scenarios/Cologne-multiseason/influenza-sentinel-weekly.csv";
	public static final String MULTISEASON_HOLIDAYS_PATH = "Scenarios/Cologne-multiseason/nrw-school-holidays-2022-26.tsv";
	public static final String MULTISEASON_AGES_PATH = "Scenarios/Cologne-multiseason/population-ages.tsv";
	public static final String MULTISEASON_WEATHER_PATH = "Scenarios/Cologne-multiseason/cologne-weather-2022-26.csv";

	/** The average year of the single-season scenario, which the weather helper continues with; not copied (decision log D3). */
	public static final String MULTISEASON_AVG_WEATHER_PATH = "Scenarios/Cologne/cologne-weather-avg-1996-2025.csv";


	/** Days in {@code recovered} before an agent is susceptible again in the multi-season scenario (decision D4). */
	static final int MULTISEASON_REFRACTORY_DAYS = 7;

	/** Days between two snapshots of the multi-season run: iterations 300, 700 and 1000 lie in the summers (decision D5). */
	static final int MULTISEASON_SNAPSHOT_INTERVAL = 100;

	/** Agents per day per weekly NRW case; a calibration prior, kept small next to local transmission. */
	private static final double IMPORT_PER_NRW_WEEKLY_CASE = 0.0025;

	/**
	 * Agents per day per weekly NRW case in the multi-season scenario: four times the constant of the single-season scenario
	 * (decision D10, revised after trial run 6: with the factor 4 the take-off of the first wave is that of the notifications,
	 * and the imported share of the infections at the peak is about 3 %).
	 */
	static final double MULTISEASON_IMPORT_PER_NRW_WEEKLY_CASE = 4 * IMPORT_PER_NRW_WEEKLY_CASE;

	static final String INPUT = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/episim/openDataModel/cologne/input/";

	private static final double CALIBRATION_PARAMETER = 1.0e-05 * 0.83 * 1.4 * 1.2 * 1.7;

	/** The scenarios this generator writes. */
	public enum Scenario {

		/** Cologne, the 2025/26 season; {@value #CONFIG_PATH} and the files next to it. */
		COLOGNE,

		/**
		 * Cologne, the four seasons 2022/23 to 2025/26 in one run (docs/influenza-multiseasonal.md);
		 * {@value #MULTISEASON_CONFIG_PATH} and the files next to it.
		 */
		COLOGNE_MULTISEASON;

		/** The scenario called {@code name}, ignoring case; the message lists the valid names. */
		public static Scenario parse(String name) {
			for (Scenario scenario : values())
				if (scenario.name().equalsIgnoreCase(name.strip().replace('-', '_')))
					return scenario;
			throw new IllegalArgumentException("Unknown scenario '" + name + "'; valid: " + java.util.Arrays.toString(values()));
		}
	}

	private GenerateInfluenzaConfig() {
	}

	public static void main(String[] args) throws IOException {
		generate(args.length == 0 ? Scenario.COLOGNE : Scenario.parse(args[0]), Path.of(""));
	}

	/**
	 * Writes the config, the progression and the policy of the scenario below {@code outputRoot}. The inputs are read
	 * relative to the working directory, and the config refers to its files by their path below {@code outputRoot}; for
	 * the empty root these are the repo-relative paths of the scenario folder.
	 */
	public static void generate(Scenario scenario, Path outputRoot) throws IOException {
		generate(scenario, outputRoot, MultiSeasonVariant.BASE);
	}

	/** As {@link #generate(Scenario, Path)}; the variant matters for {@link Scenario#COLOGNE_MULTISEASON} only. */
	public static void generate(Scenario scenario, Path outputRoot, MultiSeasonVariant variant) throws IOException {

		switch (scenario) {
			case COLOGNE -> generateCologne(outputRoot);
			case COLOGNE_MULTISEASON -> generateCologneMultiseason(outputRoot, variant);
		}
	}

	/**
	 * The four seasons 2022/23 to 2025/26 in one run (docs/influenza-multiseasonal.md, decisions D1 to D13): five strains, the
	 * explicit immunity model, a seven-day refractory period, the import of all windows, the masks of 2022/23, the school
	 * holidays and the weather of the whole horizon, snapshots every 100 days. The calibration parameter is that of the
	 * single-season scenario; the calibration of the run is a common factor on it (D13).
	 */
	private static void generateCologneMultiseason(Path outputRoot, MultiSeasonVariant variant) throws IOException {

		Config config = baseConfig();
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		var imports = MultiSeasonImport.importSchedules(
				InfluenzaParameterisation.readWeeklyCases(Path.of(MULTISEASON_IMPORT_DRIVER_PATH)),
				MULTISEASON_IMPORT_PER_NRW_WEEKLY_CASE * variant.importFactor(),
				MultiSeasonImport.readSentinel(Path.of(MULTISEASON_SENTINEL_PATH)), MultiSeasonImport.SEASONS,
				MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END, MULTISEASON_IMPORT_DRIVER_PATH);

		// the age tables of the strains scaled to one mean susceptibility over the ages of the population (decision D16)
		var allStrains = MultiSeasonStrains.normalised(MultiSeasonStrains.all(),
				MultiSeasonStrains.readAgeCounts(Path.of(MULTISEASON_AGES_PATH)));
		// the infectiousness of B/Victoria relative to the other strains (Q26 option a)
		var strains = allStrains.stream().map(spec -> spec.strain().equals(MultiSeasonStrains.BVIC)
				? new InfluenzaParameterisation.StrainSpec(spec.strain(), spec.ageSusceptibility(),
				spec.infectiousness() * variant.bvicInfectiousnessFactor())
				: spec).toList();

		InfluenzaParameterisation.configure(config, strains, imports, MultiSeasonImport.HORIZON_START,
				MULTISEASON_REFRACTORY_DAYS);
		MultiSeasonImmunity.configure(config, variant.immunity().withPriorBMaxShare(variant.priorBMaxShare()));

		episimConfig.setLeisureOutdoorFraction(variant.weatherOutdoorFraction()
				? InfluenzaParameterisation.weatherOutdoorFraction(MULTISEASON_WEATHER_PATH, MULTISEASON_AVG_WEATHER_PATH,
						MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END)
				: fixedOutdoorPattern());
		episimConfig.setSnapshotInterval(MULTISEASON_SNAPSHOT_INTERVAL);

		config.controller().setOutputDirectory("output/cologne-influenza-multiseason");

		InfluenzaParameterisation.writeProgression(episimConfig, outputRoot, MULTISEASON_PROGRESSION_PATH);
		InfluenzaParameterisation.writePolicy(episimConfig, MultiSeasonPolicy.build(Path.of(MULTISEASON_HOLIDAYS_PATH),
				MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END, variant), outputRoot, MULTISEASON_POLICY_PATH);

		writeConfig(config, outputRoot.resolve(MULTISEASON_CONFIG_PATH));
	}

	/**
	 * The fixed outdoor pattern of the single-season scenario (0.8 from mid-April to mid-September, 0.1 from mid-November to
	 * mid-February, linear in between), repeated for every year of the horizon.
	 */
	static Map<LocalDate, Double> fixedOutdoorPattern() {
		Map<LocalDate, Double> pattern = new java.util.TreeMap<>();
		for (int year = MultiSeasonImport.HORIZON_START.getYear(); year <= MultiSeasonImport.HORIZON_END.getYear(); year++) {
			pattern.put(LocalDate.of(year, 4, 15), 0.8);
			pattern.put(LocalDate.of(year, 9, 15), 0.8);
			pattern.put(LocalDate.of(year, 11, 15), 0.1);
			pattern.put(LocalDate.of(year + 1, 2, 15), 0.1);
		}
		return pattern;
	}

	private static void writeConfig(Config config, Path configFile) throws IOException {
		if (configFile.getParent() != null)
			Files.createDirectories(configFile.getParent());
		ConfigUtils.writeConfig(config, configFile.toString());
	}

	private static void generateCologne(Path outputRoot) throws IOException {

		Config config = baseConfig();

		configureInfluenza(config);

		config.controller().setOutputDirectory("output/cologne-influenza");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		InfluenzaParameterisation.writeProgression(episimConfig, outputRoot, PROGRESSION_PATH);
		InfluenzaParameterisation.writePolicy(episimConfig, buildPolicy(), outputRoot, POLICY_PATH);

		writeConfig(config, outputRoot.resolve(CONFIG_PATH));
	}

	static void configureInfluenza(Config config) throws IOException {

		InfluenzaParameterisation.configure(config, InfluenzaParameterisation.importSchedule(
				InfluenzaParameterisation.readWeeklyCases(Path.of(IMPORT_DRIVER_PATH)), IMPORT_PER_NRW_WEEKLY_CASE,
				IMPORT_DRIVER_PATH));

		// EpisimConfigGroup's default pattern moved to 2025/26
		ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class).setLeisureOutdoorFraction(Map.of(
				LocalDate.parse("2025-04-15"), 0.8,
				LocalDate.parse("2025-09-15"), 0.8,
				LocalDate.parse("2025-11-15"), 0.1,
				LocalDate.parse("2026-02-15"), 0.1,
				LocalDate.parse("2026-04-15"), 0.8));
	}

	static Config baseConfig() {

		Config config = ConfigUtils.createConfig(new EpisimConfigGroup());

		config.global().setRandomSeed(7564655870752979346L);
		config.vehicles().setVehiclesFile(INPUT + "cologne_2020-vehicles.xml.gz");
		config.plans().setInputFile(INPUT + "cologne_snz_entirePopulation_emptyPlans_withDistricts_25pt_split_grid.xml.gz");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.addInputEventsFile(INPUT + "cologne_snz_episim_events_wt_25pt_split.xml.gz")
				.addDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
		episimConfig.addInputEventsFile(INPUT + "cologne_snz_episim_events_sa_25pt_split.xml.gz")
				.addDays(DayOfWeek.SATURDAY);
		episimConfig.addInputEventsFile(INPUT + "cologne_snz_episim_events_so_25pt_split.xml.gz")
				.addDays(DayOfWeek.SUNDAY);

		episimConfig.setActivityHandling(EpisimConfigGroup.ActivityHandling.startOfDay);
		episimConfig.setCalibrationParameter(CALIBRATION_PARAMETER);
		episimConfig.setFacilitiesHandling(EpisimConfigGroup.FacilitiesHandling.snz);
		episimConfig.setSampleSize(0.25);
		episimConfig.setHospitalFactor(0.5);
		episimConfig.setThreads(8);
		episimConfig.setDaysInfectious(Integer.MAX_VALUE);
		episimConfig.setInitialInfections(Integer.MAX_VALUE);

		configureContactIntensities(episimConfig);

		return config;
	}

	/** As in SnzCologneOpenProductionScenario#configureContactIntensitiesAndSeasonality. */
	static void configureContactIntensities(EpisimConfigGroup episimConfig) {
		int spaces = 20;

		double workCiMod = 0.75;
		double leisureCiMod = 0.4;
		double schoolCiMod = 0.75;

		episimConfig.getOrAddContainerParams("pt", "tr").setContactIntensity(10.0 * workCiMod).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("work").setContactIntensity(1.47).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("leisure").setContactIntensity(9.24 * leisureCiMod).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("leisPublic").setContactIntensity(9.24 * leisureCiMod).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("leisPrivate").setContactIntensity(9.24 * leisureCiMod).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("educ_kiga").setContactIntensity(11.0 * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("educ_primary").setContactIntensity(11.0 * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("educ_secondary").setContactIntensity(11.0 * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("educ_tertiary").setContactIntensity(11. * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("educ_higher").setContactIntensity(5.5 * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("educ_other").setContactIntensity(11. * schoolCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("shop_daily").setContactIntensity(0.88).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("shop_other").setContactIntensity(0.88).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("errands").setContactIntensity(1.47).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("business").setContactIntensity(1.47 * workCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5);
		episimConfig.getOrAddContainerParams("visit").setContactIntensity(9.24 * leisureCiMod).setSpacesPerFacility(spaces).setSeasonality(0.5); // 33/3.57
		episimConfig.getOrAddContainerParams("home").setContactIntensity(1.0).setSpacesPerFacility(1).setSeasonality(0.5); // 33/33
		episimConfig.getOrAddContainerParams("quarantine_home").setContactIntensity(1.0).setSpacesPerFacility(1).setSeasonality(0.5); // 33/33
	}

	static FixedPolicy.ConfigBuilder buildPolicy() {

		FixedPolicy.ConfigBuilder policy = FixedPolicy.config();

		// school holidays NRW 2025/26, KMK
		InfluenzaParameterisation.closeSchools(policy, "2025-10-13", "2025-10-25");
		InfluenzaParameterisation.closeSchools(policy, "2025-12-22", "2026-01-06");
		InfluenzaParameterisation.closeSchools(policy, "2026-03-30", "2026-04-11");

		return policy;
	}

}
