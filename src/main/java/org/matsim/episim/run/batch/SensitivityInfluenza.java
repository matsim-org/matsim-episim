package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.VirusStrainConfigGroup;

import java.io.IOException;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Reduced susceptibility of persons aged 60+ as a stand-in for pre-season immunity; per contact, it does not change
 * the course of the disease.
 */
public class SensitivityInfluenza extends InfluenzaBatch<SensitivityInfluenza.Params> {

	@Override
	protected String runName() {
		return "influenzaSensitivity";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		if (!isSelectedSeed(params.seed) || !isSelectedInfectiousness(params.infectiousness))
			return null;

		Config config = seasonConfig(params.seed);
		VirusStrainConfigGroup.StrainParams strain = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN);
		strain.setInfectiousness(params.infectiousness);
		setElderlySusceptibility(strain, params.elderlySusceptibility);
		return config;
	}

	/** ageSusceptibility is interpolated linearly, so the factor is set as a step between 59 and 60. */
	static void setElderlySusceptibility(VirusStrainConfigGroup.StrainParams strain, double factor) {
		NavigableMap<Integer, Double> susceptibility = new TreeMap<>(strain.getAgeSusceptibility());
		if (!susceptibility.tailMap(59, true).isEmpty())
			throw new IllegalStateException("ageSusceptibility already has entries from age 59 on: " + susceptibility);
		susceptibility.put(59, 1.0);
		susceptibility.put(60, factor);
		strain.setAgeSusceptibility(susceptibility);
	}

	public static final class Params {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.35, 0.40})
		public double infectiousness;

		@Parameter({0.6})
		public double elderlySusceptibility;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(SensitivityInfluenza.class, Params.class, args, "60+ susceptibility check");
	}

}
