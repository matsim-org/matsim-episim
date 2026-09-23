package org.matsim.episim.run.legacy;

import com.google.inject.Module;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.VirusStrainConfigGroup;
import org.matsim.episim.analysis.FilterEvents;
import org.matsim.episim.analysis.HospitalNumbersFromEvents;
import org.matsim.episim.analysis.InfectionLocationsFromEvents;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.analysis.RValuesFromEvents;
import org.matsim.episim.analysis.SecondaryAttackRateFromEvents;
import org.matsim.episim.analysis.VaccinationEffectiveness;
import org.matsim.episim.analysis.VaccinationEffectivenessFromPotentialInfections;
import org.matsim.episim.model.InfectionModelWithAntibodies;
import org.matsim.run.BatchOutputPacker;
import org.matsim.run.RunParallel;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;


/**
 * boilerplate batch for cologne
 */
public class StarterBatchOpenCologne implements BatchRun<StarterBatchOpenCologne.Params> {
	private static final LocalDate START_DATE = LocalDate.of(2021, 1, 1);
	private static final int ITERATIONS = 10;
	private static final int RUN_INDEX_WIDTH = 5;

	/*
	 * here you can swap out vaccination model, antibody model, etc.
	 * See CologneBMBF202310XX_soup.java for an example
	 */
	@Nullable
	@Override
	public Module getBindings(int id, @Nullable Params params) {
		return getBindings(params);
	}


	/*
	 * here you select & modify models specified in the SnzCologneProductionScenario & SnzProductionScenario.
	 */
	private SnzCologneOpenProductionScenario getBindings(Params params) {
		return new SnzCologneOpenProductionScenario.Builder()
			.setMasks(params == null ? SnzCologneOpenProductionScenario.Masks.yes : params.masks)
			.setInfectionModel(InfectionModelWithAntibodies.class)
			.build();
	}

	/*
	 * Metadata is needed for covid-sim.
	 */
	@Override
	public Metadata getMetadata() {
		return Metadata.of("cologne", "calibration")
			.withEndDate(START_DATE.plusDays(ITERATIONS - 1L).toString());
	}

	@Override
	public LocalDate getDefaultStartDate() {
		return START_DATE;
	}


	/*
	 * Here you can add post-processing classes, that will be executed after the simulation.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		String startDate = START_DATE.toString();
		return List.of(
			new InfectionLocationsFromEvents(),
			new RValuesFromEvents().withArgs("--start-date", startDate),
			new VaccinationEffectiveness().withArgs(
				"--start-date", startDate,
				"--district", "Köln"),
			new VaccinationEffectivenessFromPotentialInfections().withArgs("--remove-infected"),
			new HospitalNumbersFromEvents().withArgs(
				"--start-date", startDate,
				"--district", "Köln"),
			new SecondaryAttackRateFromEvents().withArgs("--start-date", startDate),
			new FilterEvents().withArgs()
		);
	}

	/*
	 * Here you can specify configuration options
	 */
	@Override
	public Config prepareConfig(int id, Params params) {

		// Level 1: General (matsim) config. Here you can specify number of iterations and the seed.
		Config config = getBindings(params).config();

		config.global().setRandomSeed(params.seed);

		// Level 2: Episim specific configs:
		// 		 2a: general episim config
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.setCalibrationParameter(episimConfig.getCalibrationParameter() * params.thetaFactor);

		//		 2b: specific config groups, e.g. virusStrainConfigGroup
		VirusStrainConfigGroup virusStrainConfigGroup = ConfigUtils.addOrGetModule(config, VirusStrainConfigGroup.class);

		return config;
	}


	/*
	 * Specify parameter combinations that will be run.
	 */
	public static final class Params {
		// general
		@GenerateSeeds(2)
		public long seed;

		@Parameter({1.0, 2.0})
		public double thetaFactor;

		@EnumParameter(SnzCologneOpenProductionScenario.Masks.class)
		public SnzCologneOpenProductionScenario.Masks masks;

	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) throws IOException {
		ZonedDateTime startedAt = ZonedDateTime.now();
		OutputPaths paths = createOutputPaths(System.getenv("EPISIM_OUTPUT"));
		Path output = paths.simulation();

		String[] args2 = {
				"--output", output.toString(),
				RunParallel.OPTION_SETUP, StarterBatchOpenCologne.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(ITERATIONS),
				RunParallel.OPTION_METADATA
		};

		int exitCode = new CommandLine(new RunParallel<>()).execute(args2);
		if (exitCode != 0)
			throw new IllegalStateException("Batch run failed with exit code " + exitCode);

		writeRunNotes(output, startedAt, ZonedDateTime.now());
		new BatchOutputPacker(output, paths.visualizationWithSeeds(), "Köln", true).pack();
		new BatchOutputPacker(output, paths.visualizationWithoutSeeds(), "Köln", false).pack();
	}

	private static void writeRunNotes(Path output, ZonedDateTime startedAt, ZonedDateTime finishedAt) throws IOException {
		String user = System.getProperty("user.name", "unknown");
		String notes = """
			# Hello World Cologne Based Project

			This MATSim Episim batch was generated by `StarterBatchOpenCologne`.

			- Started by: `%s`
			- Host: `%s`
			- Started at: `%s`
			- Finished at: `%s`
			- Duration: `%s`
			""".formatted(
			user,
			resolveHostName(),
			DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startedAt),
			DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(finishedAt),
			Duration.between(startedAt, finishedAt)
		);
		Files.writeString(output.resolve("notes.md"), notes, StandardCharsets.UTF_8);
	}

	private static String resolveHostName() {
		String configuredHost = System.getenv("HOSTNAME");
		if (configuredHost != null && !configuredHost.isBlank()) return configuredHost;
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException ignored) {
			return "unknown";
		}
	}

	private static OutputPaths createOutputPaths(String configuredOutput) throws IOException {
		if (configuredOutput == null || configuredOutput.isBlank())
			return new OutputPaths(
				Path.of("output"),
				Path.of("output-vis-keep-seeds"),
				Path.of("output-vis-no-seeds")
			);

		Path runDirectory = createNextRunDirectory(Path.of(configuredOutput));
		return new OutputPaths(
			runDirectory.resolve("output"),
			runDirectory.resolve("output-vis-keep-seeds"),
			runDirectory.resolve("output-vis-no-seeds")
		);
	}

	private static Path createNextRunDirectory(Path outputRoot) throws IOException {
		Path dateDirectory = Files.createDirectories(outputRoot.resolve(LocalDate.now().toString()));
		int nextIndex;
		try (var entries = Files.list(dateDirectory)) {
			nextIndex = entries
				.filter(Files::isDirectory)
				.mapToInt(StarterBatchOpenCologne::parseRunIndex)
				.max()
				.orElse(0) + 1;
		}

		while (true) {
			Path runDirectory = dateDirectory.resolve(
				String.format(Locale.ROOT, "%0" + RUN_INDEX_WIDTH + "d", nextIndex));
			try {
				return Files.createDirectory(runDirectory);
			} catch (FileAlreadyExistsException ignored) {
				nextIndex++;
			}
		}
	}

	private static int parseRunIndex(Path directory) {
		try {
			String name = directory.getFileName().toString();
			return name.chars().allMatch(Character::isDigit) ? Integer.parseInt(name) : -1;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private record OutputPaths(
		Path simulation,
		Path visualizationWithSeeds,
		Path visualizationWithoutSeeds
	) {
	}

}
