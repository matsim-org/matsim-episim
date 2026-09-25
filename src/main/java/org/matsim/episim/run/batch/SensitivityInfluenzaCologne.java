package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;

import java.io.IOException;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Sensitivity check of {@link InfluenzaCologneScenario}: a reduced susceptibility of persons aged 60 and over, as a
 * stand-in for their pre-season immunity (vaccination, residual immunity) before that is modelled explicitly.
 *
 * <p>Motivated by the age comparison of the calibration batch at {@code infectiousness} 0.35: the model infects
 * 60+ as often as 35-59, GrippeWeb ILI and ARE show about 0.6 of it; ICOSARI SARI shows the resulting surplus of
 * hospitalisations in 60-79. {@code elderlySusceptibility} is a per-contact factor (leaky), it does not change
 * the course of the disease once a person is infected.</p>
 *
 * <p>{@code ageSusceptibility} is interpolated linearly between its keys, so the factor is set as a step with a
 * key at 59 (1.0) and one at 60; the entries below 59 stay as in {@code Scenarios/Cologne/config.xml}.</p>
 */
public class SensitivityInfluenzaCologne extends InfluenzaCologneBatch<SensitivityInfluenzaCologne.Params> {

	@Override
	protected String runName() {
		return "influenzaSensitivity";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		Config config = seasonConfig(params.seed);
		VirusStrainConfigGroup.StrainParams strain = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN);
		strain.setInfectiousness(params.infectiousness);
		setElderlySusceptibility(strain, params.elderlySusceptibility);
		return config;
	}

	/**
	 * Sets the susceptibility of persons aged 60 and over to {@code factor}, as a step with keys at 59 (1.0) and 60,
	 * see the class javadoc.
	 */
	static void setElderlySusceptibility(VirusStrainConfigGroup.StrainParams strain, double factor) {
		NavigableMap<Integer, Double> susceptibility = new TreeMap<>(strain.getAgeSusceptibility());
		if (!susceptibility.tailMap(59, true).isEmpty())
			throw new IllegalStateException("ageSusceptibility already has entries from age 59 on: " + susceptibility);
		susceptibility.put(59, 1.0);
		susceptibility.put(60, factor);
		strain.setAgeSusceptibility(susceptibility);
	}

	public static final class Params {
		@GenerateSeeds(2)
		public long seed;

		@Parameter({0.35, 0.40})
		public double infectiousness;

		@Parameter({0.6})
		public double elderlySusceptibility;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(SensitivityInfluenzaCologne.class, Params.class, "Influenza Cologne 60+ susceptibility check");
	}

}
