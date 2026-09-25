package org.matsim.episim.run.batch;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimUtils;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@link SensitivityInfluenzaCologne} (60+ susceptibility 0.6) with the leisure outdoor fraction taken from the actual
 * Cologne weather of 2025/26 instead of the fixed seasonal pattern in {@code Scenarios/Cologne/config.xml}.
 *
 * <p>Motivated by the sensitivity batch at {@code infectiousness} 0.35: its two seeds peak three weeks apart (Köln
 * 2026-W05 and 2026-W08, observed 2026-W06). The fixed pattern keeps the outdoor fraction at 0.67 in September and
 * 0.44 in October 2025, while the temperature-based fraction is 0.26 and 0.08, so transmission in the autumn is
 * damped by about a quarter to a third more than the weather supports. An offline estimate put the peak two to three
 * weeks earlier with the weather-based fraction; this batch checks that in the model itself, with four seeds because
 * the seed spread is as large as the expected shift.</p>
 *
 * <p>The outdoor fraction is computed by {@link EpisimUtils#getOutDoorFractionFromDateAndTemp2} with the parameters
 * of the VSP production scenarios (rain threshold 0.5 mm, temperature midpoint 18.5 &deg;C, range 5 &deg;C,
 * temperature weight 1, maximum 1), from the daily weather in {@value #WEATHER_PATH} and, after its last day, the
 * 1996-2025 mean in {@value #AVG_WEATHER_PATH}; provenance in the file headers. Only {@code infectiousness} is varied,
 * so {@code analyze_run.py} evaluates the batch as a calibration grid.</p>
 */
public class WeatherInfluenzaCologne extends InfluenzaCologneBatch<WeatherInfluenzaCologne.Params> {

	static final String WEATHER_PATH = "Scenarios/Cologne/cologne-weather-2025-26.csv";
	static final String AVG_WEATHER_PATH = "Scenarios/Cologne/cologne-weather-avg-1996-2025.csv";

	/** Susceptibility of persons aged 60 and over, as in {@link SensitivityInfluenzaCologne}. */
	static final double ELDERLY_SUSCEPTIBILITY = 0.6;

	private static final double RAIN_THRESHOLD = 0.5;
	private static final double T_MID = 18.5;
	private static final double T_MID_FALL_2020 = 25.0;
	private static final double T_RANGE = 5.0;

	@Override
	protected String runName() {
		return "influenzaWeather";
	}

	@Override
	public Config prepareConfig(int id, Params params) {
		Config config = seasonConfig(params.seed);
		VirusStrainConfigGroup.StrainParams strain = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class)
			.getParams(INFLUENZA_STRAIN);
		strain.setInfectiousness(params.infectiousness);
		SensitivityInfluenzaCologne.setElderlySusceptibility(strain, ELDERLY_SUSCEPTIBILITY);

		ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class).setLeisureOutdoorFraction(outdoorFraction());
		return config;
	}

	/**
	 * Weather-based outdoor fraction; the 2020 midpoints only apply before 2021 and do not matter here.
	 */
	static Map<LocalDate, Double> outdoorFraction() {
		try {
			return EpisimUtils.getOutDoorFractionFromDateAndTemp2(new File(WEATHER_PATH), new File(AVG_WEATHER_PATH),
				RAIN_THRESHOLD, T_MID, T_MID_FALL_2020, T_MID, T_MID, T_RANGE, 1.0, 1.0);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static final class Params {
		@GenerateSeeds(4)
		public long seed;

		@Parameter({0.30, 0.35})
		public double infectiousness;
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		runBatch(WeatherInfluenzaCologne.class, Params.class,
			"Influenza Cologne weather-based outdoor fraction (60+ susceptibility " + ELDERLY_SUSCEPTIBILITY + ")");
	}

}
