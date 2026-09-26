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
import org.matsim.episim.model.VirusStrain;
import org.matsim.episim.run.modules.InfluenzaScenario;
import org.matsim.episim.run.scenarios.InfluenzaParameterisation;
import org.matsim.episim.run.scenarios.ScenarioDescriptor;
import org.matsim.run.BatchOutputPacker;
import org.matsim.run.RunParallel;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.io.File;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Base of the influenza batches: runs a batch for one or more scenario folders ({@code --scenario Scenarios/<City>}),
 * packs it for covid-sim and, if {@code EPISIM_OUTPUT} is an SVN working copy, commits the viewer packages.
 * The scenario comes from a system property because {@link #getMetadata()} gets no batch parameters.
 */
public abstract class InfluenzaBatch<P> implements BatchRun<P> {

	private static final Logger log = LogManager.getLogger(InfluenzaBatch.class);

	static final String PATHOGEN = InfluenzaParameterisation.INFLUENZA.getName();

	static final VirusStrain INFLUENZA_STRAIN = InfluenzaParameterisation.INFLUENZA_STRAIN;

	static final int ITERATIONS = (int) (ChronoUnit.DAYS.between(InfluenzaParameterisation.SEASON_START,
		InfluenzaParameterisation.SEASON_END) + 1);

	private static final int RUN_INDEX_WIDTH = 5;

	/** Comma-separated infectiousness values to run, set from {@code --infectiousness}; all grid values if unset. */
	static final String INFECTIOUSNESS_PROPERTY = "episim.infectiousness";

	/** Comma-separated seeds to run, the first {@code --seeds} of the batch's generated ones. */
	static final String SEEDS_PROPERTY = "episim.seeds";

	/** Seeds run without {@code --seeds}; batches generate up to {@code @GenerateSeeds} of them. */
	static final int DEFAULT_SEEDS = 2;

	private ScenarioDescriptor descriptor;

	protected Path scenarioDirectory() {
		return InfluenzaScenario.scenarioFromProperty();
	}

	protected InfluenzaScenario module() {
		return new InfluenzaScenario(scenarioDirectory());
	}

	protected final ScenarioDescriptor descriptor() {
		if (descriptor == null)
			descriptor = ScenarioDescriptor.read(scenarioDirectory());
		return descriptor;
	}

	/** Run name in the viewer and prefix of the run ids. */
	protected String runName() {
		return PATHOGEN;
	}

	@Nullable
	@Override
	public Module getBindings(int id, @Nullable P params) {
		return module();
	}

	@Override
	public Metadata getMetadata() {
		return Metadata.of(descriptor().city(), runName())
			.withEndDate(InfluenzaParameterisation.SEASON_END.toString());
	}

	@Override
	public LocalDate getDefaultStartDate() {
		return InfluenzaParameterisation.SEASON_START;
	}

	// no vaccination analyses (NoVaccination), no HospitalNumbersFromEvents (it resamples with SARS-CoV-2 parameters)
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		String startDate = InfluenzaParameterisation.SEASON_START.toString();
		List<OutputAnalysis> analyses = new ArrayList<>(List.of(
			new InfectionLocationsFromEvents(),
			new RValuesFromEvents().withArgs("--start-date", startDate)));
		// SecondaryAttackRateFromEvents only counts the district Köln (hard-coded in matsim-episim-libs)
		if (descriptor().city().equals("Köln"))
			analyses.add(new SecondaryAttackRateFromEvents().withArgs("--start-date", startDate));
		analyses.add(new FilterEvents().withArgs());
		return analyses;
	}

	protected Config seasonConfig(long seed) {
		Config config = module().config();
		config.global().setRandomSeed(seed);
		return config;
	}

	/**
	 * Arguments: {@code --scenario <folder>} (repeated or comma-separated), {@code --seeds <number>},
	 * {@code --infectiousness <values>} (a subset of the batch's grid), {@code --tasks <runs in parallel>} and
	 * {@code --resume <run directory>}: finishes a run whose simulations are done (post-processing, packing, commit)
	 * without simulating again; it needs the same scenarios, seeds and infectiousness as the original run.
	 * With several scenarios each gets a subfolder in the output directories, and covid-sim shows
	 * {@code .../output-vis-no-seeds} as one dashboard with a tab per city. Nothing is committed unless all succeed.
	 */
	protected static void runBatch(Class<? extends InfluenzaBatch<?>> setup, Class<?> params, String[] args, String label)
		throws IOException, InterruptedException {

		Arguments arguments = parseArguments(args);
		System.setProperty(SEEDS_PROPERTY, seeds(params, arguments.seeds() != null ? arguments.seeds() : DEFAULT_SEEDS));
		if (arguments.infectiousness() != null)
			System.setProperty(INFECTIOUSNESS_PROPERTY, checkInfectiousness(params, arguments.infectiousness()));

		List<Path> scenarios = arguments.scenarios();
		if (scenarios.isEmpty())
			scenarios = List.of(InfluenzaScenario.scenarioFromProperty());

		// fail before simulating, not after hours
		List<ScenarioDescriptor> descriptors = new ArrayList<>();
		for (Path scenario : scenarios)
			descriptors.add(descriptor(setup, scenario));

		boolean multiCity = descriptors.size() > 1;
		if (multiCity) {
			long distinct = descriptors.stream().map(InfluenzaBatch::subfolder).distinct().count();
			if (distinct != descriptors.size())
				throw new IllegalArgumentException("Scenario folders must have distinct names: " + scenarios);
		}

		String configuredOutput = System.getenv("EPISIM_OUTPUT");
		OutputPaths paths = arguments.resume() != null ? existingOutputPaths(configuredOutput, arguments.resume())
			: createOutputPaths(configuredOutput);

		for (ScenarioDescriptor descriptor : descriptors) {
			System.setProperty(InfluenzaScenario.SCENARIO_PROPERTY, descriptor.directory().toString());
			OutputPaths scenarioPaths = multiCity ? paths.forScenario(subfolder(descriptor)) : paths;
			String scenarioLabel = "Influenza " + descriptor.city() + " " + label;
			if (arguments.resume() == null)
				runScenario(setup, params, descriptor, scenarioPaths, arguments.tasks(), false, scenarioLabel);
			else if (isPacked(scenarioPaths))
				log.info("{} is packed already, skipping", scenarioPaths.simulation());
			else
				runScenario(setup, params, descriptor, scenarioPaths, arguments.tasks(), true, scenarioLabel);
		}

		String cities = descriptors.stream().map(ScenarioDescriptor::city).collect(Collectors.joining(" + "));
		if (configuredOutput != null && !configuredOutput.isBlank())
			maybeCommitToSvn(Path.of(configuredOutput.strip()), paths, "Influenza " + cities + " " + label);
	}

	private static void runScenario(Class<? extends InfluenzaBatch<?>> setup, Class<?> params, ScenarioDescriptor descriptor,
									OutputPaths paths, int tasks, boolean postOnly, String label) throws IOException {

		ZonedDateTime startedAt = ZonedDateTime.now();
		Path output = paths.simulation();

		List<String> runArgs = new ArrayList<>(List.of(
			"--output", output.toString(),
			RunParallel.OPTION_SETUP, setup.getName(),
			RunParallel.OPTION_PARAMS, params.getName(),
			RunParallel.OPTION_TASKS, Integer.toString(tasks),
			RunParallel.OPTION_ITERATIONS, Integer.toString(ITERATIONS),
			RunParallel.OPTION_METADATA));
		if (postOnly) {
			if (!Files.isDirectory(output))
				throw new IllegalArgumentException("Nothing to resume in " + output);
			runArgs.add(RunParallel.OPTION_POST_ONLY);
		}

		int exitCode = new CommandLine(new RunParallel<>()).execute(runArgs.toArray(String[]::new));
		if (exitCode != 0)
			throw new IllegalStateException(label + " failed with exit code " + exitCode);

		writeRunNotes(output, label, setup, startedAt, ZonedDateTime.now());
		enrichMetadata(output.resolve("metadata.yaml"), descriptor, copyObservedData(output, descriptor));
		new BatchOutputPacker(output, paths.visualizationWithSeeds(), descriptor.city(), true).pack();
		new BatchOutputPacker(output, paths.visualizationWithoutSeeds(), descriptor.city(), false).pack();
	}

	private static ScenarioDescriptor descriptor(Class<? extends InfluenzaBatch<?>> setup, Path scenario) {
		System.setProperty(InfluenzaScenario.SCENARIO_PROPERTY, scenario.toString());
		ScenarioDescriptor descriptor = instantiate(setup).descriptor();
		if (!Files.isRegularFile(descriptor.config()))
			throw new IllegalArgumentException("No " + ScenarioDescriptor.CONFIG_FILE_NAME + " in " + descriptor.directory()
				+ "; run the scenario's GenerateInfluenzaConfig first");
		return descriptor;
	}

	private static String subfolder(ScenarioDescriptor descriptor) {
		return descriptor.directory().toAbsolutePath().normalize().getFileName().toString();
	}

	record Arguments(List<Path> scenarios, @Nullable Integer seeds, @Nullable String infectiousness, int tasks,
					 @Nullable String resume) {
	}

	static Arguments parseArguments(String[] args) {
		List<Path> scenarios = new ArrayList<>();
		Integer seeds = null;
		String infectiousness = null;
		String resume = null;
		int tasks = 1;
		for (int i = 0; i < args.length; i += 2) {
			if (i + 1 == args.length)
				throw usage(args);
			String value = args[i + 1];
			switch (args[i]) {
				case "--scenario" -> {
					for (String folder : value.split(","))
						if (!folder.isBlank())
							scenarios.add(Path.of(folder.strip()));
				}
				case "--seeds" -> seeds = Integer.parseInt(value);
				case "--infectiousness" -> infectiousness = value;
				case "--tasks" -> tasks = Integer.parseInt(value);
				case "--resume" -> resume = value;
				default -> throw usage(args);
			}
		}
		if (tasks < 1)
			throw new IllegalArgumentException("--tasks must be at least 1");
		return new Arguments(scenarios, seeds, infectiousness, tasks, resume);
	}

	private static IllegalArgumentException usage(String[] args) {
		return new IllegalArgumentException("Unexpected arguments " + String.join(" ", args)
			+ "; usage: --scenario Scenarios/<City> [--scenario ...] [--seeds N] [--infectiousness 0.3,0.35] [--tasks N] [--resume <run dir>]");
	}

	/** Whether this seed is one of the first {@code --seeds}; batches return no config otherwise. */
	protected static boolean isSelectedSeed(long seed) {
		String selection = System.getProperty(SEEDS_PROPERTY);
		return selection == null || selection.isBlank()
			|| Arrays.stream(selection.split(",")).map(Long::parseLong).anyMatch(s -> s == seed);
	}

	/** The first {@code count} seeds that {@code @GenerateSeeds} of the params class generates, comma-separated. */
	private static String seeds(Class<?> params, int count) {
		GenerateSeeds generated;
		try {
			generated = params.getField("seed").getAnnotation(GenerateSeeds.class);
		} catch (NoSuchFieldException e) {
			generated = null;
		}
		if (generated == null)
			throw new IllegalArgumentException(params.getName() + " generates no seeds");
		if (count < 1 || count > generated.value())
			throw new IllegalArgumentException("--seeds must be between 1 and " + generated.value());

		// the same sequence BatchRun.prepare generates
		Random rnd = new Random(generated.seed());
		List<String> seeds = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			long seed = rnd.nextLong();
			seeds.add(Long.toString(i == 0 ? generated.first() : seed));
		}
		return String.join(",", seeds);
	}

	/** Whether this infectiousness was asked for with {@code --infectiousness}; batches return no config otherwise. */
	protected static boolean isSelectedInfectiousness(double infectiousness) {
		String selection = System.getProperty(INFECTIOUSNESS_PROPERTY);
		return selection == null || selection.isBlank()
			|| parseValues(selection).stream().anyMatch(v -> Math.abs(v - infectiousness) < 1e-9);
	}

	/** Checks the values against the {@code infectiousness} grid of the params class, so a typo fails before simulating. */
	private static String checkInfectiousness(Class<?> params, String selection) {
		Parameter grid;
		try {
			grid = params.getField("infectiousness").getAnnotation(Parameter.class);
		} catch (NoSuchFieldException e) {
			grid = null;
		}
		if (grid == null)
			throw new IllegalArgumentException(params.getName() + " has no infectiousness grid");

		List<Double> values = parseValues(selection);
		for (double value : values)
			if (Arrays.stream(grid.value()).noneMatch(v -> Math.abs(v - value) < 1e-9))
				throw new IllegalArgumentException("infectiousness " + value + " is not on the grid " + Arrays.toString(grid.value()));
		return selection;
	}

	private static List<Double> parseValues(String values) {
		return Arrays.stream(values.split(",")).map(String::strip).filter(v -> !v.isEmpty()).map(Double::parseDouble).toList();
	}

	private static InfluenzaBatch<?> instantiate(Class<? extends InfluenzaBatch<?>> setup) {
		try {
			return setup.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not create " + setup.getName(), e);
		}
	}

	/** Copies the observed data into the output; format of the entries: covid-sim src/util/observedData.ts. */
	private static List<Map<String, Object>> copyObservedData(Path output, ScenarioDescriptor descriptor) throws IOException {
		Path directory = Files.createDirectories(output.resolve(BatchOutputPacker.OBSERVED_DIRECTORY));

		List<Map<String, Object>> observed = new ArrayList<>();
		for (ScenarioDescriptor.Observed data : descriptor.observed()) {
			Path source = descriptor.observedFile(data);
			Path fileName = source.getFileName();
			Files.copy(source, directory.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);

			Map<String, Object> definition = new LinkedHashMap<>();
			definition.put("name", data.name());
			definition.put("url", BatchOutputPacker.OBSERVED_DIRECTORY + "/" + fileName);
			definition.put("date", "week");
			definition.put("value", data.value());
			definition.put("period", "week");
			definition.put("unit", data.unit());
			if (data.unit().equals("count"))
				definition.put("population", data.population());
			definition.put("plots", List.of(data.plot()));
			observed.add(definition);
		}
		return observed;
	}

	private static void enrichMetadata(Path metadataFile, ScenarioDescriptor descriptor, List<Map<String, Object>> observed)
		throws IOException {
		ObjectMapper mapper = new ObjectMapper(new YAMLFactory()
			.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		Map<String, Object> metadata = mapper.readValue(metadataFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
		});
		metadata.put("pathogen", PATHOGEN);
		metadata.put("country", descriptor.country());
		metadata.put("region", descriptor.region());
		metadata.put("city", descriptor.city());
		metadata.put("observed", observed);
		mapper.writeValue(metadataFile.toFile(), metadata);
	}

	/** Commits the viewer packages if {@code root} is versioned; the raw output stays local. */
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

	/** Credentials from {@code SVN_USERNAME} and {@code SVN_PASSWORD_FILE} if set, otherwise from svn's own cache. */
	private static void runSvn(String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of("svn", "--non-interactive"));
		String username = System.getenv("SVN_USERNAME");
		String passwordFile = System.getenv("SVN_PASSWORD_FILE");
		if (username != null && !username.isBlank())
			command.addAll(List.of("--username", username, "--no-auth-cache"));
		if (passwordFile != null && !passwordFile.isBlank())
			command.add("--password-from-stdin");
		command.addAll(List.of(args));

		ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
		if (passwordFile != null && !passwordFile.isBlank())
			builder.redirectInput(new File(passwordFile));
		Process process = builder.start();
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

	/** An existing run directory, absolute or relative to {@code EPISIM_OUTPUT}. */
	private static OutputPaths existingOutputPaths(String configuredOutput, String resume) {
		Path runDirectory = Path.of(resume);
		if (!runDirectory.isAbsolute() && configuredOutput != null && !configuredOutput.isBlank())
			runDirectory = Path.of(configuredOutput.strip()).resolve(resume);
		if (!Files.isDirectory(runDirectory.resolve("output")))
			throw new IllegalArgumentException("No run to resume in " + runDirectory);
		return new OutputPaths(runDirectory.resolve("output"), runDirectory.resolve("output-vis-keep-seeds"),
			runDirectory.resolve("output-vis-no-seeds"), runDirectory);
	}

	/** Both viewer packages written; a partial one has to be removed before resuming. */
	private static boolean isPacked(OutputPaths paths) {
		boolean withSeeds = Files.exists(paths.visualizationWithSeeds().resolve("metadata.yaml"));
		boolean withoutSeeds = Files.exists(paths.visualizationWithoutSeeds().resolve("metadata.yaml"));
		if (withSeeds != withoutSeeds)
			throw new IllegalStateException("Only one viewer package of " + paths.simulation() + " exists; remove it and resume");
		return withSeeds;
	}

	private static Path createNextRunDirectory(Path outputRoot) throws IOException {
		Path dateDirectory = Files.createDirectories(outputRoot.resolve(LocalDate.now().toString()));
		int nextIndex;
		try (var entries = Files.list(dateDirectory)) {
			nextIndex = entries
				.filter(Files::isDirectory)
				.mapToInt(InfluenzaBatch::parseRunIndex)
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
		OutputPaths forScenario(String subfolder) {
			return new OutputPaths(simulation.resolve(subfolder), visualizationWithSeeds.resolve(subfolder),
				visualizationWithoutSeeds.resolve(subfolder), runDirectory);
		}
	}

}
