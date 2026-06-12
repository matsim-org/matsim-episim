package org.matsim.episim.run.batch;

import com.google.inject.Module;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.model.InfectionModelWithAntibodies;
import org.matsim.episim.run.modules.SnzCologneOpenScenario;
import org.matsim.run.RunParallel;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;


/**
 * boilerplate batch for cologne
 */
public class StarterBatchOpenCologne implements BatchRun<StarterBatchOpenCologne.Params> {
	// yyyyyy could we please have a regression test around this here?

	@Nullable
	@Override
	public Module getBindings(int id, @Nullable Params params) {
		return getBindings(params);
	}

	/**
	 * here you select & modify models specified in the SnzCologneProductionScenario & SnzProductionScenario.
	 * And/or swap out vaccination model, antibody model, etc. See CologneBMBF202310XX_soup.java for an example
	 */
	private SnzCologneOpenScenario getBindings( @Nullable Params params) {
		// (this is a separate private method since it actually needs to be used consistent in two different places :-(.
		// In one case, only the config is needed.  In the other case, the bindings are needed, possibly including the config.)
		return new SnzCologneOpenScenario.Builder()
			.setMasks(params == null ? SnzCologneOpenScenario.Masks.yes : params.masks)
			.setInfectionModel(InfectionModelWithAntibodies.class)
			.build();
	}

	/**
	 * Metadata is needed for covid-sim website (www.covid-sim.info)
	 */
	@Override
	public Metadata getMetadata() {
		return Metadata.of("cologne", "calibration");
	}


	/**
	 * Here you can add post-processing classes, that will be executed after the simulation.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		return List.of();
	}

	/**
	 * Here you can specify configuration options
	 */
	@Override
	public Config prepareConfig(int id, Params params) {
		// take the config out of the bindings
		Config config = getBindings(params).config();

		// Level 1: General (matsim) config. Here you can specify number of iterations and the seed.
		config.global().setRandomSeed(params.seed);

		// Level 2: Episim specific configs:
		// 		 2a: general episim config
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		episimConfig.setCalibrationParameter(episimConfig.getCalibrationParameter() * params.thetaFactor);

		//		 2b: specific config groups, e.g. virusStrainConfigGroup
		VirusStrainConfigGroup virusStrainConfigGroup = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);

		return config;
	}


	/**
	 * Class that contains parameter ranges for the simultaneously running batch runs.  Works by auto-magic; the framework goes
	 * through this class, gets all fields via reflection, expects them to be annotated in the right way, and then builds the batch
	 * runs for these.
	 */
	public static final class Params {
		@GenerateSeeds(2)
		public long seed;

		@Parameter({1.0, 2.0})
		public double thetaFactor;

		@EnumParameter(SnzCologneOpenScenario.Masks.class)
		public SnzCologneOpenScenario.Masks masks;

	}



	/**
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, StarterBatchOpenCologne.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(10),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
		/// ({@link RunParallel} is central infrastructure.  It will (I guess):
		/// * take the "main" class from {@link RunParallel.OPTION_SETUP}.  In the case here, this is the present class.
		/// * take the "Params" class from {@link RunParallel.OPTION_SETUP}.  In the case here, it is specified above ({@link Params).
		///  The way in which this is constructed, this does not have to be started from here, but can also be started with the
		///  OPTION_ params on the command line.
	}

}

