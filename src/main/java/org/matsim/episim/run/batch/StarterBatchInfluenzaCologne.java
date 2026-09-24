package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;

import java.io.IOException;

/**
 * Seeded batch of {@link InfluenzaCologneScenario} with its config as it is; running, packing and uploading are
 * described in {@link InfluenzaCologneBatch}.
 */
public class StarterBatchInfluenzaCologne extends InfluenzaCologneBatch<StarterBatchInfluenzaCologne.Params> {

	@Override
	public Config prepareConfig(int id, Params params) {
		return seasonConfig(params.seed);
	}

	public static final class Params {
		@GenerateSeeds(2)
		public long seed;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(StarterBatchInfluenzaCologne.class, Params.class, "Influenza Cologne run");
	}

}
