package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;

import java.io.IOException;

/**
 * Plain seeded run of the influenza season, e.g. {@code --scenario Scenarios/Cologne --scenario Scenarios/Berlin}.
 */
public class RunInfluenza extends InfluenzaBatch<RunInfluenza.Params> {

	@Override
	public Config prepareConfig(int id, Params params) {
		if (!isSelectedSeed(params.seed))
			return null;
		return seasonConfig(params.seed);
	}

	public static final class Params {
		@GenerateSeeds(10)
		public long seed;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(RunInfluenza.class, Params.class, args, "run");
	}

}
