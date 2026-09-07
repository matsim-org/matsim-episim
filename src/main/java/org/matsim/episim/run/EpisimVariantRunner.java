/*-
 * #%L
 * MATSim Episim
 * %%
 * Copyright (C) 2020 matsim-org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * #L%
 */
package org.matsim.episim.run;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.episim.EpisimModule;
import org.matsim.episim.EpisimRunner;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.run.batch.StarterBatchOpenCologne;
import org.matsim.episim.run.modules.SnzCologneOpenProductionScenario;
import picocli.CommandLine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs and publishes exactly one parameter variant. */
@CommandLine.Command(
	name = "run-variant",
	description = "Run one Cologne scenario variant and publish its result to SVN.",
	mixinStandardHelpOptions = true,
	showDefaultValues = true
)
public final class EpisimVariantRunner implements Callable<Integer> {

	private static final Logger log = LogManager.getLogger(EpisimVariantRunner.class);

	@CommandLine.Option(names = "--seed", required = true, description = "MATSim/Episim random seed.")
	private long seed;

	@CommandLine.Option(names = "--gamma", required = true,
		description = "Multiplier applied to the scenario calibration parameter.")
	private double gamma;

	@CommandLine.Option(names = "--iterations", defaultValue = "10", description = "Number of simulated days.")
	private int iterations;

	@CommandLine.Option(names = "--masks", defaultValue = "yes", description = "Enable or disable masks.")
	private SnzCologneOpenProductionScenario.Masks masks;

	@CommandLine.Option(names = "--run-id",
		description = "Unique SVN directory name. Defaults to a value derived from seed and gamma.")
	private String runId;

	@CommandLine.Option(names = "--work-root", defaultValue = "${env:EPISIM_WORK_ROOT:-work}",
		description = "Local parent directory for simulation and publication artifacts.")
	private Path workRoot;

	@CommandLine.Option(names = "--svn-url", required = true,
		description = "Existing SVN directory below which the run directory is created.")
	private String svnUrl;

	@CommandLine.Option(names = "--svn-username-file",
		defaultValue = "${env:SVN_USERNAME_FILE:-/var/run/secrets/episim/svn-username}",
		description = "File containing the SVN username.")
	private Path svnUsernameFile;

	@CommandLine.Option(names = "--svn-password-file",
		defaultValue = "${env:SVN_PASSWORD_FILE:-/var/run/secrets/episim/svn-password}",
		description = "File containing the SVN password.")
	private Path svnPasswordFile;

	@CommandLine.Option(names = "--svn-command", defaultValue = "svn", hidden = true)
	private String svnCommand;

	public static void main(String[] args) {
		System.exit(new CommandLine(new EpisimVariantRunner()).execute(args));
	}

	@Override
	public Integer call() throws Exception {
		validateOptions();
		if (runId == null || runId.isBlank()) runId = defaultRunId(seed, gamma, masks);
		validateRunId(runId);

		String requestJson = requestJson(runId, seed, gamma, iterations, masks);
		SvnResultPublisher publisher = new SvnResultPublisher(
			svnCommand, svnUrl, svnUsernameFile, svnPasswordFile);

		if (publisher.isAlreadyPublished(runId, requestJson)) {
			log.info("Variant {} is already published with the same request", runId);
			return 0;
		}

		Path runDirectory = createRunDirectory(workRoot, runId);
		Path simulationOutput = Files.createDirectory(runDirectory.resolve("simulation"));
		Path publication = Files.createDirectory(runDirectory.resolve("publication"));

		Instant startedAt = Instant.now();
		Config config = runSimulation(simulationOutput, runId);
		Instant finishedAt = Instant.now();

		Files.writeString(publication.resolve("request.json"), requestJson, StandardCharsets.UTF_8);
		Files.writeString(publication.resolve("metadata.json"),
			metadataJson(runId, seed, gamma, iterations, masks, startedAt, finishedAt),
			StandardCharsets.UTF_8);
		ConfigUtils.writeConfig(config, publication.resolve("config.xml").toString());
		createZip(simulationOutput, publication.resolve("results.zip"));
		Files.createFile(publication.resolve("_SUCCESS"));

		publisher.publish(runId, publication, requestJson);
		log.info("Variant {} successfully published to {}", runId, publisher.targetUrl(runId));
		return 0;
	}

	private Config runSimulation(Path output, String runId) throws IOException {
		StarterBatchOpenCologne batch = new StarterBatchOpenCologne();
		StarterBatchOpenCologne.Params params = new StarterBatchOpenCologne.Params();
		params.seed = seed;
		params.thetaFactor = gamma;
		params.masks = masks;

		Config config = batch.prepareConfig(1, params);
		config.controller().setOutputDirectory(output.toString());
		config.controller().setRunId(runId);

		Module scenarioBindings = batch.getBindings(1, params);
		Module base = scenarioBindings == null
			? new EpisimModule()
			: Modules.override(new EpisimModule()).with(scenarioBindings);
		Module configured = Modules.override(base).with(new ConfigModule(config));
		Injector injector = Guice.createInjector(configured);

		OutputDirectoryLogging.catchLogEntries();
		OutputDirectoryLogging.initLoggingWithOutputDirectory(output.toString());
		try {
			injector.getInstance(EpisimRunner.class).run(iterations);
			for (OutputAnalysis analysis : batch.postProcessing()) {
				log.info("Running analysis {}", analysis.getClass().getSimpleName());
				injector.injectMembers(analysis);
				analysis.analyzeOutput(output);
			}
		} finally {
			OutputDirectoryLogging.closeOutputDirLogging();
		}
		return config;
	}

	private void validateOptions() {
		if (!Double.isFinite(gamma) || gamma <= 0)
			throw new CommandLine.ParameterException(new CommandLine(this), "--gamma must be finite and greater than zero");
		if (iterations <= 0)
			throw new CommandLine.ParameterException(new CommandLine(this), "--iterations must be greater than zero");
	}

	static String defaultRunId(long seed, double gamma, SnzCologneOpenProductionScenario.Masks masks) {
		String gammaId = String.format(Locale.ROOT, "%s", gamma).replace('.', 'p');
		return "seed_" + seed + "-gamma_" + gammaId + "-masks_" + masks;
	}

	private static void validateRunId(String value) {
		if (!value.matches("[A-Za-z0-9_-]+"))
			throw new IllegalArgumentException("run-id may contain only letters, digits, '_' and '-'; dots break post-processing");
	}

	private static Path createRunDirectory(Path root, String id) throws IOException {
		Files.createDirectories(root);
		Path directory = root.resolve(id);
		return Files.createDirectory(directory);
	}

	private static String requestJson(String id, long seed, double gamma, int iterations,
		SnzCologneOpenProductionScenario.Masks masks) {
		return """
			{
			  "runId": "%s",
			  "seed": %d,
			  "gamma": %s,
			  "iterations": %d,
			  "masks": "%s"
			}
			""".formatted(jsonEscape(id), seed, Double.toString(gamma), iterations, masks);
	}

	private static String metadataJson(String id, long seed, double gamma, int iterations,
		SnzCologneOpenProductionScenario.Masks masks, Instant startedAt, Instant finishedAt) {
		return """
			{
			  "runId": "%s",
			  "seed": %d,
			  "gamma": %s,
			  "iterations": %d,
			  "masks": "%s",
			  "startedAt": "%s",
			  "finishedAt": "%s",
			  "durationSeconds": %d,
			  "status": "success"
			}
			""".formatted(
			jsonEscape(id), seed, Double.toString(gamma), iterations, masks,
			startedAt, finishedAt, Duration.between(startedAt, finishedAt).toSeconds());
	}

	private static String jsonEscape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	static void createZip(Path source, Path archive) throws IOException {
		try (OutputStream file = Files.newOutputStream(archive);
			 ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8);
			 var entries = Files.walk(source)) {
			for (Path path : entries.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
				String name = source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
				zip.putNextEntry(new ZipEntry(name));
				Files.copy(path, zip);
				zip.closeEntry();
			}
		}
	}

	private static final class ConfigModule extends AbstractModule {
		private final Config config;

		private ConfigModule(Config config) {
			this.config = config;
		}

		@Override
		protected void configure() {
			bind(Config.class).toInstance(config);
		}
	}
}
