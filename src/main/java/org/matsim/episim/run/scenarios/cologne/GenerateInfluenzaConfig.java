package org.matsim.episim.run.scenarios.cologne;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * Writes the Cologne influenza scenario ({@value #CONFIG_PATH}, {@value #PROGRESSION_PATH}, {@value #POLICY_PATH}):
 * the non-COVID part of the VSP Cologne COVID scenario on the public open-data inputs, plus
 * {@link InfluenzaParameterisation}. Run from the repo root.
 */
public final class GenerateInfluenzaConfig {

	public static final String CONFIG_PATH = "Scenarios/Cologne/config.xml";
	public static final String PROGRESSION_PATH = "Scenarios/Cologne/progression.conf";
	public static final String POLICY_PATH = "Scenarios/Cologne/policy.conf";

	public static final String IMPORT_DRIVER_PATH = "Scenarios/Cologne/nrw-ifsg-influenza-2025-26.tsv";

	/** Agents per day per weekly NRW case; a calibration prior, kept small next to local transmission. */
	private static final double IMPORT_PER_NRW_WEEKLY_CASE = 0.0025;

	static final String INPUT = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/episim/openDataModel/cologne/input/";

	private static final double CALIBRATION_PARAMETER = 1.0e-05 * 0.83 * 1.4 * 1.2 * 1.7;

	private GenerateInfluenzaConfig() {
	}

	public static void main(String[] args) throws IOException {

		Config config = baseConfig();

		configureInfluenza(config);

		config.controller().setOutputDirectory("output/cologne-influenza");

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		InfluenzaParameterisation.writeProgression(episimConfig, PROGRESSION_PATH);
		InfluenzaParameterisation.writePolicy(episimConfig, buildPolicy(), POLICY_PATH);

		ConfigUtils.writeConfig(config, CONFIG_PATH);
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
