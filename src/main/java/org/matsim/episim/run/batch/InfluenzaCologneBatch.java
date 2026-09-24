package org.matsim.episim.run.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.inject.Module;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.episim.BatchRun;
import org.matsim.episim.analysis.FilterEvents;
import org.matsim.episim.analysis.InfectionLocationsFromEvents;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.analysis.RValuesFromEvents;
import org.matsim.episim.analysis.SecondaryAttackRateFromEvents;
import org.matsim.episim.model.Pathogen;
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.run.modules.InfluenzaCologneScenario;
import org.matsim.episim.run.scenarios.cologne.GenerateInfluenzaConfig;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What every batch of {@link InfluenzaCologneScenario} shares: bindings, metadata, post-processing, and
 * {@link #runBatch}, which runs the batch, packs it for the viewer and uploads it. Subclasses only choose their
 * parameters and how they change the config.
 *
 * <p>Compared with {@code org.matsim.episim.run.legacy.StarterBatchOpenCologne}:</p>
 * <ul>
 *   <li><b>Actually uploads to SVN.</b> The legacy class only ever wrote to whatever local directory
 *       {@code EPISIM_OUTPUT} pointed at &mdash; typically an SVN working copy, but committing it was left to a
 *       human. When {@code EPISIM_OUTPUT} is a versioned directory of an SVN working copy, the two viewer packages
 *       of a successful batch are committed; the raw simulation output stays local. A batch with a failed task is
 *       neither packed nor uploaded ({@code RunParallel} exits non-zero then).</li>
 *   <li><b>Richer, explicit metadata.</b> {@code metadata.yaml} gets {@code pathogen}, {@code country},
 *       {@code region} and {@code city} as their own top-level keys, so the viewer can filter and correlate a run
 *       with matching RKI surveillance series. The values follow the naming convention of
 *       {@code scripts/influenza-statistics} ({@code country: "DE"}, German {@code Land}/{@code Stadt} names).</li>
 * </ul>
 */
public abstract class InfluenzaCologneBatch<P> implements BatchRun<P> {

	private static final Logger log = LogManager.getLogger(InfluenzaCologneBatch.class);

	static final String COUNTRY = "DE";
	static final String REGION = "Nordrhein-Westfalen";
	static final String CITY = "Köln";
	static final String PATHOGEN = "influenza";

	static final VirusStrain INFLUENZA_STRAIN = VirusStrain.of(new Pathogen(PATHOGEN), PATHOGEN);

	/** Simulates the whole 2025/26 season, see {@link InfluenzaCologneScenario#SEASON_END}. */
	static final int ITERATIONS = (int) (ChronoUnit.DAYS.between(InfluenzaCologneScenario.SEASON_START,
		InfluenzaCologneScenario.SEASON_END) + 1);

	private static final int RUN_INDEX_WIDTH = 5;

	/** Population of Nordrhein-Westfalen behind RKI's IfSG incidences (Fallzahl / Inzidenz in the 2025/26 season). */
	static final int NRW_POPULATION = 18_041_000;

	static final String SARI_PATH = "Scenarios/Cologne/germany-icosari-influenza-sari-2025-26.tsv";

	/**
	 * Surveillance data the viewer shows next to the model; see the {@code observed} format in covid-sim
	 * ({@code src/util/observedData.ts}). {@code url} is relative to the batch output, where the file is copied.
	 */
	private static final List<ObservedData> OBSERVED = List.of(
		new ObservedData(Path.of(GenerateInfluenzaConfig.IMPORT_DRIVER_PATH), observedDefinition(
			"Influenza notifications NRW (RKI IfSG)", "cases", "count", "casCom")),
		new ObservedData(Path.of(SARI_PATH), observedDefinition(
			"Influenza SARI hospitalisations Germany (RKI ICOSARI)", "incidence", "per100k", "hospNewCas"))
	);

	private record ObservedData(Path source, Map<String, Object> definition) {
	}

	/**
	 * Name of the batch, used as run name in the viewer and as prefix of the run ids and output files.
	 */
	protected String runName() {
		return PATHOGEN;
	}

	@Nullable
	@Override
	public Module getBindings(int id, @Nullable P params) {
		return new InfluenzaCologneScenario();
	}

	/*
	 * Metadata is needed for covid-sim; pathogen/country/region are added on top of this after the
	 * run finishes, see enrichMetadata().
	 */
	@Override
	public Metadata getMetadata() {
		return Metadata.of(CITY, runName())
			.withEndDate(InfluenzaCologneScenario.SEASON_END.toString());
	}

	@Override
	public LocalDate getDefaultStartDate() {
		return InfluenzaCologneScenario.SEASON_START;
	}

	/*
	 * Omitted on purpose:
	 * - vaccination analyses: InfluenzaCologneScenario binds NoVaccination;
	 * - HospitalNumbersFromEvents: it ignores the run's config and re-samples hospitalisations from the infection
	 *   events with its own SARS-CoV-2 setup (lags, lengths of stay, strain factors). Influenza hospital and ICU
	 *   counts come from the simulation itself (nSeriouslySick / nCritical in infections.txt), which is also what
	 *   the viewer package reads.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		String startDate = InfluenzaCologneScenario.SEASON_START.toString();
		return List.of(
			new InfectionLocationsFromEvents(),
			new RValuesFromEvents().withArgs("--start-date", startDate),
			new SecondaryAttackRateFromEvents().withArgs("--start-date", startDate),
			new FilterEvents().withArgs()
		);
	}

	/**
	 * The scenario's config with the given random seed.
	 */
	protected static Config seasonConfig(long seed) {
		Config config = new InfluenzaCologneScenario().config();
		config.global().setRandomSeed(seed);
		return config;
	}

	/**
	 * Runs the batch into the next run directory below {@code EPISIM_OUTPUT} (or {@code output*} in the working
	 * directory), packs it for the viewer and, if {@code EPISIM_OUTPUT} is versioned, commits the packages to SVN.
	 *
	 * @param label names the batch in {@code notes.md} and in the commit message
	 */
	protected static void runBatch(Class<? extends InfluenzaCologneBatch<?>> setup, Class<?> params, String label)
		throws IOException, InterruptedException {

		ZonedDateTime startedAt = ZonedDateTime.now();
		String configuredOutput = System.getenv("EPISIM_OUTPUT");
		OutputPaths paths = createOutputPaths(configuredOutput);
		Path output = paths.simulation();

		String[] args = {
			"--output", output.toString(),
			RunParallel.OPTION_SETUP, setup.getName(),
			RunParallel.OPTION_PARAMS, params.getName(),
			RunParallel.OPTION_TASKS, Integer.toString(1),
			RunParallel.OPTION_ITERATIONS, Integer.toString(ITERATIONS),
			RunParallel.OPTION_METADATA
		};

		int exitCode = new CommandLine(new RunParallel<>()).execute(args);
		if (exitCode != 0)
			throw new IllegalStateException("Batch run failed with exit code " + exitCode);

		writeRunNotes(output, label, setup, startedAt, ZonedDateTime.now());
		enrichMetadata(output.resolve("metadata.yaml"), copyObservedData(output));
		new BatchOutputPacker(output, paths.visualizationWithSeeds(), CITY, true).pack();
		new BatchOutputPacker(output, paths.visualizationWithoutSeeds(), CITY, false).pack();

		if (configuredOutput != null && !configuredOutput.isBlank())
			maybeCommitToSvn(Path.of(configuredOutput.strip()), paths, label);
	}

	private static Map<String, Object> observedDefinition(String name, String value, String unit, String plot) {
		Map<String, Object> definition = new LinkedHashMap<>();
		definition.put("name", name);
		definition.put("date", "week");
		definition.put("value", value);
		definition.put("period", "week");
		definition.put("unit", unit);
		if (unit.equals("count"))
			definition.put("population", NRW_POPULATION);
		definition.put("plots", List.of(plot));
		return definition;
	}

	/**
	 * Copies the surveillance data into the batch output, so the viewer package keeps the data as of this batch,
	 * and returns their {@code observed} entries for the metadata.
	 */
	private static List<Map<String, Object>> copyObservedData(Path output) throws IOException {
		Path directory = Files.createDirectories(output.resolve(BatchOutputPacker.OBSERVED_DIRECTORY));

		List<Map<String, Object>> observed = new ArrayList<>();
		for (ObservedData data : OBSERVED) {
			Path fileName = data.source().getFileName();
			Files.copy(data.source(), directory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);

			Map<String, Object> definition = new LinkedHashMap<>();
			definition.put("name", data.definition().get("name"));
			definition.put("url", BatchOutputPacker.OBSERVED_DIRECTORY + "/" + fileName);
			definition.putAll(data.definition());
			observed.add(definition);
		}
		return observed;
	}

	/**
	 * Adds {@code pathogen}, {@code country}, {@code region} and the {@code observed} data to the metadata.yaml
	 * {@code RunParallel --write-metadata} already wrote (which only has {@code city}, from
	 * {@link #getMetadata()}'s region argument); overwrites {@code city} too, so all four live
	 * together as plain top-level keys.
	 */
	private static void enrichMetadata(Path metadataFile, List<Map<String, Object>> observed) throws IOException {
		ObjectMapper mapper = new ObjectMapper(new YAMLFactory()
			.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		Map<String, Object> metadata = mapper.readValue(metadataFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
		});
		metadata.put("pathogen", PATHOGEN);
		metadata.put("country", COUNTRY);
		metadata.put("region", REGION);
		metadata.put("city", CITY);
		metadata.put("observed", observed);
		mapper.writeValue(metadataFile.toFile(), metadata);
	}

	/**
	 * Commits the two viewer packages to SVN if {@code root} ({@code EPISIM_OUTPUT}) is a versioned
	 * directory of a working copy; does nothing (just logs) otherwise, e.g. a plain directory or no
	 * {@code svn} CLI. The raw simulation output (~170 MB per seed) stays local and unversioned.
	 *
	 * <p>The check is on {@code root}, not on the run directory: that one was only just created and
	 * is never versioned yet. {@code svn add --parents} schedules the new date and run directories
	 * (without their other content) along with the packages, and the commit targets the topmost
	 * newly added directory, so only what was added here is committed. A failing add or commit
	 * throws, so a broken upload is not mistaken for a successful one.</p>
	 */
	private static void maybeCommitToSvn(Path root, OutputPaths paths, String label) throws IOException, InterruptedException {
		if (!isVersioned(root)) {
			log.info("{} is not a versioned directory of an SVN working copy, skipping svn upload", root);
			return;
		}

		Path runDirectory = paths.runDirectory();
		Path commitTarget = runDirectory;
		for (Path dir = runDirectory.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
			if (!isVersioned(dir))
				commitTarget = dir;
		}

		runSvn("add", "--parents", "--force",
			paths.visualizationWithSeeds().toString(), paths.visualizationWithoutSeeds().toString());
		String message = label + " " + root.relativize(runDirectory) + " (viewer packages)";
		runSvn("commit", "-m", message, commitTarget.toString());
		log.info("Committed viewer packages of {} to SVN", runDirectory);
	}

	private static boolean isVersioned(Path path) {
		try {
			Process process = new ProcessBuilder("svn", "info", path.toString())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			return process.waitFor() == 0;
		} catch (IOException e) {
			// svn CLI not installed
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void runSvn(String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of("svn"));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).inheritIO().start();
		int exit = process.waitFor();
		if (exit != 0)
			throw new IllegalStateException("svn " + String.join(" ", args) + " failed with exit code " + exit);
	}

	private static void writeRunNotes(Path output, String label, Class<?> setup, ZonedDateTime startedAt,
									  ZonedDateTime finishedAt) throws IOException {
		String notes = """
			# %s

			This MATSim Episim batch was generated by `%s`.

			- Started by: `%s`
			- Host: `%s`
			- Started at: `%s`
			- Finished at: `%s`
			- Duration: `%s`
			""".formatted(
			label,
			setup.getSimpleName(),
			System.getProperty("user.name", "unknown"),
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
				Path.of("output-vis-no-seeds"),
				Path.of(".")
			);

		Path runDirectory = createNextRunDirectory(Path.of(configuredOutput.strip()));
		return new OutputPaths(
			runDirectory.resolve("output"),
			runDirectory.resolve("output-vis-keep-seeds"),
			runDirectory.resolve("output-vis-no-seeds"),
			runDirectory
		);
	}

	private static Path createNextRunDirectory(Path outputRoot) throws IOException {
		Path dateDirectory = Files.createDirectories(outputRoot.resolve(LocalDate.now().toString()));
		int nextIndex;
		try (var entries = Files.list(dateDirectory)) {
			nextIndex = entries
				.filter(Files::isDirectory)
				.mapToInt(InfluenzaCologneBatch::parseRunIndex)
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
		Path visualizationWithoutSeeds,
		Path runDirectory
	) {
	}

}
