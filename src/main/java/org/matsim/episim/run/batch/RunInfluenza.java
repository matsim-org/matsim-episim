package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;

import java.io.IOException;
import java.util.Arrays;

/**
 * The influenza season of one or more scenarios. With {@code --infectiousness} it runs those values of the strain
 * infectiousness (a slider in the viewer), otherwise the value in the scenario's config.
 */
public class RunInfluenza extends InfluenzaBatch<Object> {

	@Override
	public Config prepareConfig(int id, Object params) {
		if (params instanceof GridParams grid) {
			if (!isSelectedSeed(grid.seed) || !isSelectedInfectiousness(grid.infectiousness))
				return null;
			Config config = seasonConfig(grid.seed);
			ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
				.getParams(INFLUENZA_STRAIN)
				.setInfectiousness(grid.infectiousness);
			return config;
		}

		Params plain = (Params) params;
		if (!isSelectedSeed(plain.seed))
			return null;
		return seasonConfig(plain.seed);
	}

	public static final class Params {
		@GenerateSeeds(10)
		public long seed;
	}

	public static final class GridParams {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		Class<?> params = Arrays.asList(args).contains("--infectiousness") ? GridParams.class : Params.class;
		runBatch(RunInfluenza.class, params, args, "run");
	}

}
