package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;

import java.io.IOException;

/**
 * The influenza season of one or more scenarios. With {@code --infectiousness} it runs those values of the strain
 * infectiousness (a slider in the viewer), otherwise the value in the scenario's config, which has to be on the grid.
 * Either way the infectiousness is a parameter of the run.
 */
public class RunInfluenza extends InfluenzaBatch<RunInfluenza.GridParams> {

	@Override
	public Config prepareConfig(int id, GridParams params) {
		if (!isSelectedSeed(params.seed) || !isSelectedInfectiousness(params.infectiousness))
			return null;
		Config config = seasonConfig(params.seed);
		ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN)
			.setInfectiousness(params.infectiousness);
		return config;
	}

	public static final class GridParams {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(RunInfluenza.class, GridParams.class, args, "run");
	}

}
