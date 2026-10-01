package org.matsim.episim.run.scenarios.berlin;

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
import java.util.TreeMap;

/**
 * Writes the Berlin influenza scenario ({@value #CONFIG_PATH}, {@value #PROGRESSION_PATH}, {@value #POLICY_PATH}):
 * the non-COVID part of the VSP Berlin production scenario on the public open-data inputs, plus
 * {@link InfluenzaParameterisation}. Run from the repo root.
 */
public final class GenerateInfluenzaConfig {

	public static final String CONFIG_PATH = "Scenarios/Berlin/config.xml";
	public static final String PROGRESSION_PATH = "Scenarios/Berlin/progression.conf";
	public static final String POLICY_PATH = "Scenarios/Berlin/policy.conf";

	public static final String IMPORT_DRIVER_PATH = "Scenarios/Berlin/berlin-ifsg-influenza-2025-26.tsv";

	public static final String WEATHER_PATH = "Scenarios/Berlin/berlin-weather-2025-26.csv";
	public static final String AVG_WEATHER_PATH = "Scenarios/Berlin/berlin-weather-avg-1996-2025.csv";

	public static final String MULTISEASON_CONFIG_PATH = "Scenarios/Berlin-multiseason/config.xml";
	public static final String MULTISEASON_PROGRESSION_PATH = "Scenarios/Berlin-multiseason/progression.conf";
	public static final String MULTISEASON_POLICY_PATH = "Scenarios/Berlin-multiseason/policy.conf";

	public static final String MULTISEASON_IMPORT_DRIVER_PATH = "Scenarios/Berlin-multiseason/berlin-ifsg-influenza-2022-26.tsv";
	public static final String MULTISEASON_SENTINEL_PATH = "Scenarios/Berlin-multiseason/influenza-sentinel-weekly.csv";
	public static final String MULTISEASON_HOLIDAYS_PATH = "Scenarios/Berlin-multiseason/berlin-school-holidays-2022-26.tsv";
	public static final String MULTISEASON_WEATHER_PATH = "Scenarios/Berlin-multiseason/berlin-weather-2022-26.csv";

	/** The ages of the Berlin population, which scale the age tables of the strains to one mean susceptibility (decision D16). */
	public static final String MULTISEASON_AGES_PATH = "Scenarios/Berlin-multiseason/population-ages.tsv";

	/** Days in {@code recovered} before an agent is susceptible again, as in the Cologne multi-season scenario (D4). */
	static final int MULTISEASON_REFRACTORY_DAYS = 7;

	/** Days between two snapshots of the multi-season run (D5). */
	static final int MULTISEASON_SNAPSHOT_INTERVAL = 100;

	static final String INPUT = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/episim/openDataModel/berlin/input/";

	/** Agents per day per weekly case; same per-capita import as Cologne: 0.0025 * 18.041M / 588124 * 1207780 / 3.705M. */
	private static final double IMPORT_PER_BERLIN_WEEKLY_CASE = 0.025;

	/** Four times the single-season constant, as for Cologne (D10). */
	static final double MULTISEASON_IMPORT_PER_BERLIN_WEEKLY_CASE = 4 * IMPORT_PER_BERLIN_WEEKLY_CASE;

	/** Berlin production value for AgeAndProgressionDependentInfectionModelWithSeasonality. */
	private static final double CALIBRATION_PARAMETER = 1.6E-5;

	private GenerateInfluenzaConfig() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length > 0 && args[0].equalsIgnoreCase("berlin-multiseason"))
			generateMultiseason(Path.of(""), MultiSeasonVariant.BASE);
		else
			generate(Path.of(""));
	}

	/**
	 * The four seasons 2022/23 to 2025/26 in one run, the counterpart of the Cologne multi-season scenario (docs/influenza-multiseasonal.md):
	 * the import from the IfSG notifications of Berlin and the national sentinel, the masks of 2022/23, the school holidays and the
	 * weather of Berlin over the whole horizon, snapshots every 100 days. The contact structure and the calibration parameter are
	 * those of the single-season scenario of Berlin.
	 */
	public static void generateMultiseason(Path outputRoot, MultiSeasonVariant variant) throws IOException {

		Config config = baseConfig();
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		var imports = MultiSeasonImport.importSchedules(
				InfluenzaParameterisation.readWeeklyCases(Path.of(MULTISEASON_IMPORT_DRIVER_PATH)),
				MULTISEASON_IMPORT_PER_BERLIN_WEEKLY_CASE * variant.importFactor(),
				MultiSeasonImport.readSentinel(Path.of(MULTISEASON_SENTINEL_PATH)), MultiSeasonImport.SEASONS,
				MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END, MULTISEASON_IMPORT_DRIVER_PATH);

		var strains = MultiSeasonStrains.normalised(MultiSeasonStrains.all(),
						MultiSeasonStrains.readAgeCounts(Path.of(MULTISEASON_AGES_PATH))).stream()
				.map(spec -> spec.strain().equals(MultiSeasonStrains.BVIC)
						? new InfluenzaParameterisation.StrainSpec(spec.strain(), spec.ageSusceptibility(),
						spec.infectiousness() * variant.bvicInfectiousnessFactor())
						: spec).toList();

		InfluenzaParameterisation.configure(config, strains, imports, MultiSeasonImport.HORIZON_START,
				MULTISEASON_REFRACTORY_DAYS);
		MultiSeasonImmunity.configure(config, variant.immunity().withPriorBMaxShare(variant.priorBMaxShare()));

		episimConfig.setLeisureOutdoorFraction(InfluenzaParameterisation.weatherOutdoorFraction(MULTISEASON_WEATHER_PATH,
				AVG_WEATHER_PATH, MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END));
		episimConfig.setSnapshotInterval(MULTISEASON_SNAPSHOT_INTERVAL);

		config.controller().setOutputDirectory("output/berlin-influenza-multiseason");

		InfluenzaParameterisation.writeProgression(episimConfig, outputRoot, MULTISEASON_PROGRESSION_PATH);
		InfluenzaParameterisation.writePolicy(episimConfig, MultiSeasonPolicy.build(Path.of(MULTISEASON_HOLIDAYS_PATH),
				MultiSeasonImport.HORIZON_START, MultiSeasonImport.HORIZON_END, variant), outputRoot, MULTISEASON_POLICY_PATH);

		Path configFile = outputRoot.resolve(MULTISEASON_CONFIG_PATH);
		if (configFile.getParent() != null)
			Files.createDirectories(configFile.getParent());
		ConfigUtils.writeConfig(config, configFile.toString());
	}

	/**
	 * Writes the config, the progression and the policy below {@code outputRoot}; the inputs are read relative to the
	 * working directory, and for the empty root the config refers to the repo-relative paths of the scenario folder.
	 */
	public static void generate(Path outputRoot) throws IOException {

		Config config = baseConfig();

		InfluenzaParameterisation.configure(config, InfluenzaParameterisation.importSchedule(
				InfluenzaParameterisation.readWeeklyCases(Path.of(IMPORT_DRIVER_PATH)), IMPORT_PER_BERLIN_WEEKLY_CASE,
				IMPORT_DRIVER_PATH));

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		episimConfig.setLeisureOutdoorFraction(outdoorFraction());

		InfluenzaParameterisation.writeProgression(episimConfig, outputRoot, PROGRESSION_PATH);
		InfluenzaParameterisation.writePolicy(episimConfig, buildPolicy(), outputRoot, POLICY_PATH);

		Path configFile = outputRoot.resolve(CONFIG_PATH);
		if (configFile.getParent() != null)
			Files.createDirectories(configFile.getParent());
		ConfigUtils.writeConfig(config, configFile.toString());
	}

	static Config baseConfig() {

		Config config = ConfigUtils.createConfig(new EpisimConfigGroup());

		config.global().setRandomSeed(7564655870752979346L);
		config.vehicles().setVehiclesFile(INPUT + "be_2020-vehicles.xml.gz");
		config.plans().setInputFile(INPUT + "be_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz");
		config.controller().setOutputDirectory("output/berlin-influenza");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.addInputEventsFile(INPUT + "be_2020-week_snz_episim_events_wt_25pt_split.xml.gz")
				.addDays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
		episimConfig.addInputEventsFile(INPUT + "be_2020-week_snz_episim_events_sa_25pt_split.xml.gz")
				.addDays(DayOfWeek.SATURDAY);
		episimConfig.addInputEventsFile(INPUT + "be_2020-week_snz_episim_events_so_25pt_split.xml.gz")
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

	/** As in SnzProductionScenario#configureContactIntensities; only leisure is seasonal. */
	static void configureContactIntensities(EpisimConfigGroup episimConfig) {
		int spaces = 20;

		episimConfig.getOrAddContainerParams("pt", "tr").setContactIntensity(10.0).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("work").setContactIntensity(1.47).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("leisure").setContactIntensity(9.24).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("leisPublic").setContactIntensity(9.24).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("leisPrivate").setContactIntensity(9.24).setSpacesPerFacility(spaces).setSeasonality(1.0);
		episimConfig.getOrAddContainerParams("educ_kiga").setContactIntensity(11.0).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("educ_primary").setContactIntensity(11.0).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("educ_secondary").setContactIntensity(11.0).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("educ_tertiary").setContactIntensity(11.).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("educ_higher").setContactIntensity(5.5).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("educ_other").setContactIntensity(11.).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("shop_daily").setContactIntensity(0.88).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("shop_other").setContactIntensity(0.88).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("errands").setContactIntensity(1.47).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("business").setContactIntensity(1.47).setSpacesPerFacility(spaces);
		episimConfig.getOrAddContainerParams("visit").setContactIntensity(9.24).setSpacesPerFacility(spaces); // 33/3.57
		episimConfig.getOrAddContainerParams("home").setContactIntensity(1.0).setSpacesPerFacility(1); // 33/33
		episimConfig.getOrAddContainerParams("quarantine_home").setContactIntensity(1.0).setSpacesPerFacility(1); // 33/33
	}

	static Map<LocalDate, Double> outdoorFraction() {
		Map<LocalDate, Double> fractions = new TreeMap<>();
		InfluenzaParameterisation.weatherOutdoorFraction(WEATHER_PATH, AVG_WEATHER_PATH).forEach((date, fraction) -> {
			if (!date.isBefore(InfluenzaParameterisation.SEASON_START) && !date.isAfter(InfluenzaParameterisation.SEASON_END))
				fractions.put(date, fraction);
		});
		return fractions;
	}

	static FixedPolicy.ConfigBuilder buildPolicy() {

		FixedPolicy.ConfigBuilder policy = FixedPolicy.config();

		// school holidays Berlin (= Brandenburg) 2025/26, KMK
		InfluenzaParameterisation.closeSchools(policy, "2025-10-20", "2025-11-01");
		InfluenzaParameterisation.closeSchools(policy, "2025-12-22", "2026-01-02");
		InfluenzaParameterisation.closeSchools(policy, "2026-02-02", "2026-02-07");
		InfluenzaParameterisation.closeSchools(policy, "2026-03-30", "2026-04-10");
		InfluenzaParameterisation.closeSchools(policy, "2026-05-15", "2026-05-15");

		return policy;
	}

}
