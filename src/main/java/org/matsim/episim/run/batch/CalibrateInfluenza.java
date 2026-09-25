package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;

import java.io.IOException;

/**
 * Grid over the strain {@code infectiousness}; {@code --infectiousness} and {@code --seeds} pick the runs.
 * Evaluate with {@code analyze_run.py}.
 */
public class CalibrateInfluenza extends InfluenzaBatch<CalibrateInfluenza.Params> {

	@Override
	protected String runName() {
		return "influenzaCalibration";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		if (!isSelectedSeed(params.seed) || !isSelectedInfectiousness(params.infectiousness))
			return null;

		Config config = seasonConfig(params.seed);
		ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN)
			.setInfectiousness(params.infectiousness);
		return config;
	}

	public static final class Params {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.00})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(CalibrateInfluenza.class, Params.class, args, "infectiousness calibration");
	}

}
