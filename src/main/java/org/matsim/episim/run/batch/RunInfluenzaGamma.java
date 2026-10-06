package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;

import java.io.IOException;

/**
 * The influenza batch of a scenario with several strains, such as {@code Scenarios/Cologne-multiseason}: all strains keep the
 * infectiousness of the config, and the run is calibrated by one <b>common factor γ</b> on the calibration parameter
 * (docs/influenza-multiseasonal.md, decision D13). Because the infection probability is a product with the calibration
 * parameter and the infectiousness of the strain, γ scales the transmissibility of every strain alike.
 *
 * <p>With {@code --gamma} it runs those values of γ (a slider in the viewer), otherwise γ = 1, the config as it is, which has
 * to be on the grid. Either way γ is a parameter of the run. {@code --infectiousness} belongs to {@link RunInfluenza}.</p>
 */
public class RunInfluenzaGamma extends InfluenzaBatch<RunInfluenzaGamma.GridParams> {

	@Override
	public Config prepareConfig(int id, GridParams params) {
		if (!isSelectedSeed(params.seed) || !isSelectedGamma(params.gamma))
			return null;
		Config config = seasonConfig(params.seed);
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);
		episimConfig.setCalibrationParameter(episimConfig.getCalibrationParameter() * params.gamma);
		return config;
	}

	/**
	 * The grid of γ: 0.05 to 1.50 in steps of 0.05, which contains 1.0. Provisional: range and step of the grid are an open
	 * question (Q21) until the long runs show where the fit lies; the lower end was 0.30 until Berlin needed less.
	 */
	public static final class GridParams {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90,
			0.95, 1.00, 1.05, 1.10, 1.15, 1.20, 1.25, 1.30, 1.35, 1.40, 1.45, 1.50})
		public double gamma;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(RunInfluenzaGamma.class, GridParams.class, args, "run");
	}

}
