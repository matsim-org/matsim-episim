package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;

import java.io.IOException;

/**
 * Calibration batch for the strain {@code infectiousness} of {@link InfluenzaCologneScenario}: a grid of values,
 * each with two seeds, everything else as in {@code Scenarios/Cologne/config.xml}.
 *
 * <p>The target is the growth rate of the rising phase of the 2025/26 influenza SARI hospitalisation incidence
 * (ICOSARI, Germany, about 0.070/day over 2025-W47..W51); the NRW notifications (IfSG, about 0.056/day) are a
 * cross-check. Evaluate with {@code python3 Scenarios/Cologne/analyze_run.py <batch output>}, which fits the
 * model's growth rate per grid value and interpolates the value that meets the target. The grid is centred on
 * ~0.5, estimated from the uncalibrated run: its growth rate of ~0.23/day at a generation time of ~3.6 d means
 * R ~ 2.3, the target R ~ 1.25-1.3.</p>
 *
 * <p>The packed batch has {@code infectiousness} as a measure in its metadata, so the viewer offers it as a
 * slider; the package without seeds averages the two seeds of each value.</p>
 */
public class CalibrateInfluenzaCologne extends InfluenzaCologneBatch<CalibrateInfluenzaCologne.Params> {

	@Override
	protected String runName() {
		return "influenzaCalibration";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		Config config = seasonConfig(params.seed);
		ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN)
			.setInfectiousness(params.infectiousness);
		return config;
	}

	public static final class Params {
		@GenerateSeeds(2)
		public long seed;

		@Parameter({0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(CalibrateInfluenzaCologne.class, Params.class, "Influenza Cologne infectiousness calibration");
	}

}
