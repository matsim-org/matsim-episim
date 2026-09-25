package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@link SensitivityInfluenza} with the leisure outdoor fraction computed from the scenario's {@code weather} files
 * instead of the one in its config.
 */
public class WeatherInfluenza extends InfluenzaBatch<WeatherInfluenza.Params> {

	static final double ELDERLY_SUSCEPTIBILITY = 0.6;

	@Override
	protected String runName() {
		return "influenzaWeather";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		if (!isSelectedSeed(params.seed) || !isSelectedInfectiousness(params.infectiousness))
			return null;

		Config config = seasonConfig(params.seed);
		VirusStrainConfigGroup.StrainParams strain = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN);
		strain.setInfectiousness(params.infectiousness);
		SensitivityInfluenza.setElderlySusceptibility(strain, ELDERLY_SUSCEPTIBILITY);

		ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class).setLeisureOutdoorFraction(outdoorFraction());
		return config;
	}

	Map<LocalDate, Double> outdoorFraction() {
		Path[] weather = descriptor().weatherFiles();
		return InfluenzaParameterisation.weatherOutdoorFraction(weather[0].toString(), weather[1].toString());
	}

	public static final class Params {
		@GenerateSeeds(10)
		public long seed;

		@Parameter({0.30, 0.35})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(WeatherInfluenza.class, Params.class, args,
			"weather-based outdoor fraction (60+ susceptibility " + ELDERLY_SUSCEPTIBILITY + ")");
	}

}
