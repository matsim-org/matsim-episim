package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.TracingConfigGroup;
import org.matsim.episim.policy.FixedPolicy;
import org.matsim.episim.policy.Restriction;
import org.matsim.episim.run.modules.OpenBerlinScenario;
import org.matsim.run.RunParallel;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.util.Map;

/**
 * Example batch for Open Berlin Scenario using multiple seeds, different calibration parameters and a few options.
 */
public class OpenBerlinBatch implements BatchRun<OpenBerlinBatch.Params> {


	@Override
	public OpenBerlinScenario getBindings(int id, @Nullable Params params) {
		return new OpenBerlinScenario();
	}

	@Override
	public Metadata getMetadata() {
		return Metadata.of("berlin", "batch");
	}

	@Override
	public Config prepareConfig(int id, Params params) {

		OpenBerlinScenario module = getBindings(id, params);
		Config config = module.config();
		config.global().setRandomSeed(params.seed);

		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.setCalibrationParameter(params.calibrationParameter);

		// Set the tracing capacity
		TracingConfigGroup tracingConfig = ConfigUtils.addOrGetModule(config, TracingConfigGroup.class);
		tracingConfig.setTracingCapacity_pers_per_day(Map.of(
				LocalDate.of(2020, 4, 1), 30,
				LocalDate.of(2020, 6, 15), params.tracingCapacity));


		// restrict edu activities
		FixedPolicy.ConfigBuilder builder = FixedPolicy.config();
		if (params.holidays.equals("yes"))
			builder.restrict("2020-10-12", Restriction.of(0.0), "edu");

		episimConfig.setPolicy(FixedPolicy.class, builder.build());

		return config;
	}

	public static final class Params {

		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.001, 0.01, 0.1})
		public double calibrationParameter;

		@StringParameter({"no", "yes"})
		public String holidays;

		@IntParameter({200, Integer.MAX_VALUE})
		int tracingCapacity;

	}

	public static void main(String[] args) {
		String[] args2 = new String[]{
				"--setup", OpenBerlinBatch.class.getName(),
				"--params", OpenBerlinBatch.Params.class.getName(),
				"--threads", Integer.toString(4),
				"--iterations", Integer.toString(360),
				"--write-metadata"
		};
		RunParallel.main(args2 );
	}



}
