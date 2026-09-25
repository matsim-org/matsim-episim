package org.matsim.episim.run.modules;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.PathogenConfigGroup;
import org.matsim.episim.TracingConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.model.AgeAndProgressionDependentInfectionModelWithSeasonality;
import org.matsim.episim.model.AntibodyModel;
import org.matsim.episim.model.ContactModel;
import org.matsim.episim.model.InfectionModel;
import org.matsim.episim.model.SymmetricContactModel;
import org.matsim.episim.model.activity.ActivityParticipationModel;
import org.matsim.episim.model.activity.DefaultParticipationModel;
import org.matsim.episim.model.progression.AgeDependentDiseaseStatusTransitionModel;
import org.matsim.episim.model.progression.DiseaseStatusTransitionModel;
import org.matsim.episim.model.testing.DefaultTestingModel;
import org.matsim.episim.model.testing.TestingModel;
import org.matsim.episim.model.vaccination.NoVaccination;
import org.matsim.episim.model.vaccination.VaccinationModel;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.policy.ShutdownPolicy;
import org.matsim.episim.run.scenarios.ScenarioDescriptor;
import org.matsim.vehicles.VehicleType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime module of an influenza scenario: loads {@code Scenarios/<City>/config.xml} and binds the models, the same
 * for every city. Run from the repo root, since the paths in the config are relative to it.
 */
public class InfluenzaScenario extends AbstractModule {

	public static final String SCENARIO_PROPERTY = "episim.scenario";

	private static final double VEHICLE_CAPACITY_FACTOR = 1.3;

	private final Path scenarioDirectory;

	/** For {@code --modules}. */
	public InfluenzaScenario() {
		this(scenarioFromProperty());
	}

	public InfluenzaScenario(Path scenarioDirectory) {
		this.scenarioDirectory = scenarioDirectory;
	}

	public static Path scenarioFromProperty() {
		String scenario = System.getProperty(SCENARIO_PROPERTY);
		if (scenario == null || scenario.isBlank())
			throw new IllegalStateException("No scenario given; set -D" + SCENARIO_PROPERTY + "=Scenarios/<City>");
		return Path.of(scenario.strip());
	}

	public Path getScenarioDirectory() {
		return scenarioDirectory;
	}

	@Override
	protected void configure() {

		bind(ContactModel.class).to(SymmetricContactModel.class).in(Singleton.class);
		bind(InfectionModel.class).to(AgeAndProgressionDependentInfectionModelWithSeasonality.class).in(Singleton.class);
		bind(DiseaseStatusTransitionModel.class).to(AgeDependentDiseaseStatusTransitionModel.class).in(Singleton.class);
		bind(VaccinationModel.class).to(NoVaccination.class).in(Singleton.class);
		bind(TestingModel.class).to(DefaultTestingModel.class).in(Singleton.class);
		bind(ShutdownPolicy.class).to(FixedPolicy.class).in(Singleton.class);
		bind(ActivityParticipationModel.class).to(DefaultParticipationModel.class);

		// unused for influenza, but EpisimModule requires the binding
		AntibodyModel.Config antibodyConfig = new AntibodyModel.Config();
		antibodyConfig.setImmuneResponseSigma(3.0);
		bind(AntibodyModel.Config.class).toInstance(antibodyConfig);
	}

	@Provides
	@Singleton
	public Config config() {

		Path file = scenarioDirectory.resolve(ScenarioDescriptor.CONFIG_FILE_NAME);
		if (!Files.isRegularFile(file))
			throw new IllegalArgumentException("No " + ScenarioDescriptor.CONFIG_FILE_NAME + " in scenario folder "
				+ scenarioDirectory.toAbsolutePath() + "; run the scenario's GenerateInfluenzaConfig first");

		// register episim's groups, otherwise MATSim rejects them as unmaterialized
		Config config = ConfigUtils.loadConfig(file.toString(),
				new EpisimConfigGroup(), new TracingConfigGroup(), new VirusStrainConfigGroup(), new PathogenConfigGroup());

		// reject a config without a policy file instead of running without school holidays
		String policy = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class).getPolicyConfig();
		if (policy == null || policy.equals("null"))
			throw new IllegalStateException("The config in " + scenarioDirectory + " refers to no policy file; "
				+ "write policy.conf with the scenario's GenerateInfluenzaConfig");

		return config;
	}

	@Provides
	@Singleton
	public Scenario scenario(Config config) {

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
		ControllerUtils.checkConfigConsistencyAndWriteToLog(config, "before loading scenario");

		final Scenario scenario = ScenarioUtils.loadScenario(config);

		configureVehicleCapacities(scenario, VEHICLE_CAPACITY_FACTOR);

		return scenario;
	}

	private static void configureVehicleCapacities(Scenario scenario, double capFactor) {
		for (VehicleType vehicleType : scenario.getVehicles().getVehicleTypes().values()) {
			switch (vehicleType.getId().toString()) {
				case "bus":
					vehicleType.getCapacity().setSeats((int) (70 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (40 * capFactor));
					break;
				case "metro":
					vehicleType.getCapacity().setSeats((int) (200 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (550 * capFactor));
					break;
				case "plane":
					vehicleType.getCapacity().setSeats((int) (200 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (0 * capFactor));
					break;
				case "pt":
					vehicleType.getCapacity().setSeats((int) (70 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (70 * capFactor));
					break;
				case "ship":
					vehicleType.getCapacity().setSeats((int) (150 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (150 * capFactor));
					break;
				case "train":
					vehicleType.getCapacity().setSeats((int) (250 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (750 * capFactor));
					break;
				case "tram":
					vehicleType.getCapacity().setSeats((int) (84 * capFactor));
					vehicleType.getCapacity().setStandingRoom((int) (216 * capFactor));
					break;
				default:
					throw new IllegalStateException("Unexpected value=|" + vehicleType.getId().toString() + "|");
			}
		}
	}

}
