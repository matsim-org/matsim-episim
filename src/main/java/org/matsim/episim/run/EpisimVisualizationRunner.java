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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.run.BatchOutputPacker;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Collects immutable variant results from SVN and publishes a viewer package. */
@CommandLine.Command(
	name = "visualize",
	description = "Collect listed SVN runs, pack them for the Episim viewer, and publish the package to SVN.",
	mixinStandardHelpOptions = true,
	showDefaultValues = true
)
public final class EpisimVisualizationRunner implements Callable<Integer> {

	private static final Logger log = LogManager.getLogger(EpisimVisualizationRunner.class);
	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final LocalDate START_DATE = LocalDate.of(2021, 1, 1);

	@CommandLine.Option(names = "--runs-file", required = true,
		description = "JSON manifest containing an ordered 'runs' array of published run IDs.")
	private Path runsFile;

	@CommandLine.Option(names = "--source-svn-url", required = true,
		description = "SVN directory containing the immutable per-run result directories.")
	private String sourceSvnUrl;

	@CommandLine.Option(names = "--target-svn-url", required = true,
		description = "Existing SVN directory below which the visualization directory is created.")
	private String targetSvnUrl;

	@CommandLine.Option(names = "--visualization-id", required = true,
		description = "Immutable directory name for the published viewer package.")
	private String visualizationId;

	@CommandLine.Option(names = "--district", defaultValue = "Köln",
		description = "District retained from infections.txt.")
	private String district;

	@CommandLine.Option(names = "--keep-seeds", defaultValue = "false",
		description = "Keep every seed separately instead of averaging equal non-seed parameters.")
	private boolean keepSeeds;

	@CommandLine.Option(names = "--work-root", defaultValue = "${env:EPISIM_WORK_ROOT:-work}",
		description = "Local parent directory for downloaded and generated artifacts.")
	private Path workRoot;

	@CommandLine.Option(names = "--svn-username-file",
		defaultValue = "${env:SVN_USERNAME_FILE:-/var/run/secrets/episim/svn-username}")
	private Path svnUsernameFile;

	@CommandLine.Option(names = "--svn-password-file",
		defaultValue = "${env:SVN_PASSWORD_FILE:-/var/run/secrets/episim/svn-password}")
	private Path svnPasswordFile;

	@CommandLine.Option(names = "--svn-command", defaultValue = "svn", hidden = true)
	private String svnCommand;

	public static void main(String[] args) {
		System.exit(new CommandLine(new EpisimVisualizationRunner()).execute(args));
	}

	@Override
	public Integer call() throws Exception {
		validateId(visualizationId, "visualization-id");
		if (district.isBlank()) throw new IllegalArgumentException("district must not be blank");

		List<String> runIds = readManifest(runsFile);
		String requestJson = visualizationRequestJson(
			visualizationId, stripTrailingSlashes(sourceSvnUrl), district, keepSeeds, runIds);
		SvnResultPublisher target = new SvnResultPublisher(
			svnCommand, targetSvnUrl, svnUsernameFile, svnPasswordFile);
		if (target.isAlreadyPublished(visualizationId, requestJson)) {
			log.info("Visualization {} is already published with the same request", visualizationId);
			return 0;
		}

		Files.createDirectories(workRoot);
		Path work = Files.createTempDirectory(workRoot, visualizationId + "-");
		Path downloads = Files.createDirectory(work.resolve("downloads"));
		Path batch = Files.createDirectory(work.resolve("batch"));
		Path viewer = work.resolve("viewer");

		SvnResultPublisher source = new SvnResultPublisher(
			svnCommand, sourceSvnUrl, svnUsernameFile, svnPasswordFile);
		String sourceRoot = stripTrailingSlashes(sourceSvnUrl);
		for (String runId : runIds) {
			log.info("Downloading run {}", runId);
			source.export(sourceRoot + "/" + runId, downloads.resolve(runId));
		}

		assembleBatch(downloads, batch, runIds);
		new BatchOutputPacker(batch, viewer, district, keepSeeds).pack();
		Files.writeString(viewer.resolve("request.json"), requestJson, StandardCharsets.UTF_8);
		Files.copy(runsFile, viewer.resolve("runs.json"), StandardCopyOption.REPLACE_EXISTING);
		Files.createFile(viewer.resolve("_SUCCESS"));

		target.publish(visualizationId, viewer, requestJson);
		log.info("Visualization {} successfully published to {}", visualizationId, target.targetUrl(visualizationId));
		return 0;
	}

	static List<String> readManifest(Path path) throws IOException {
		RunManifest manifest = JSON.readValue(path.toFile(), RunManifest.class);
		if (manifest.runs() == null || manifest.runs().isEmpty())
			throw new IllegalArgumentException("runs manifest must contain at least one run ID");
		Set<String> unique = new LinkedHashSet<>();
		for (String id : manifest.runs()) {
			validateId(id, "run ID");
			if (!unique.add(id)) throw new IllegalArgumentException("duplicate run ID in manifest: " + id);
		}
		return List.copyOf(unique);
	}

	static void assembleBatch(Path downloads, Path batch, List<String> runIds) throws IOException {
		List<RunRequest> requests = new ArrayList<>();
		for (String runId : runIds) {
			Path publication = downloads.resolve(runId);
			if (!Files.isRegularFile(publication.resolve("_SUCCESS")))
				throw new IllegalArgumentException("Run is incomplete (missing _SUCCESS): " + runId);
			RunRequest request = JSON.readValue(publication.resolve("request.json").toFile(), RunRequest.class);
			if (!runId.equals(request.runId()))
				throw new IllegalArgumentException("Manifest ID does not match request.json for " + runId);
			requests.add(request);

			Path runOutput = Files.createDirectory(batch.resolve(runId));
			extractZip(publication.resolve("results.zip"), runOutput);
			prefixTopLevelFiles(runOutput, runId);
			Files.copy(publication.resolve("config.xml"), runOutput.resolve(runId + ".config.xml"),
				StandardCopyOption.REPLACE_EXISTING);
		}

		writeInfo(batch.resolve("_info.txt"), requests);
		writeMetadata(batch.resolve("metadata.yaml"), requests);
		Files.writeString(batch.resolve("notes.md"),
			"# EPISim distributed visualization\n\nCollected from " + requests.size() + " immutable SVN runs.\n",
			StandardCharsets.UTF_8);
	}

	private static void extractZip(Path archive, Path target) throws IOException {
		try (InputStream file = Files.newInputStream(archive);
			 ZipInputStream zip = new ZipInputStream(file, StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				Path destination = target.resolve(entry.getName()).normalize();
				if (!destination.startsWith(target))
					throw new IllegalArgumentException("Unsafe ZIP entry: " + entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
				} else {
					if (destination.getParent() != null) Files.createDirectories(destination.getParent());
					Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
				}
				zip.closeEntry();
			}
		}
	}

	private static void prefixTopLevelFiles(Path directory, String runId) throws IOException {
		try (var files = Files.list(directory)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (!name.startsWith(runId + "."))
					Files.move(file, directory.resolve(runId + "." + name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void writeInfo(Path path, List<RunRequest> requests) throws IOException {
		StringBuilder info = new StringBuilder("RunScript;Config;RunId;Output;seed;gamma;iterations;masks\n");
		for (RunRequest request : requests) {
			info.append("run;config.xml;").append(request.runId()).append(';').append(request.runId()).append(';')
				.append(request.seed()).append(';').append(request.gamma()).append(';')
				.append(request.iterations()).append(';').append(request.masks()).append('\n');
		}
		Files.writeString(path, info, StandardCharsets.UTF_8);
	}

	private static void writeMetadata(Path path, List<RunRequest> requests) throws IOException {
		int iterations = requests.stream().mapToInt(RunRequest::iterations).max().orElseThrow();
		String metadata = """
			---
			readme: notes.md
			zip: summaries.zip
			info: _info.txt
			zipFolder: summaries
			timestamp: %s
			viewerVersion: 2
			city: cologne
			runName: calibration
			defaultStartDate: %s
			endDate: %s
			startDates:
			  - %s
			optionGroups:
			  - day: -1
			    heading: ''
			    subheading: ''
			    measures:
			      - measure: gamma
			        title: Gamma
			      - measure: iterations
			        title: Iterations
			      - measure: masks
			        title: Masks
			""".formatted(LocalDate.now(), START_DATE, START_DATE.plusDays(iterations - 1L), START_DATE);
		Files.writeString(path, metadata, StandardCharsets.UTF_8);
	}

	private static String visualizationRequestJson(String id, String sourceUrl, String district,
		boolean keepSeeds, List<String> runIds) throws IOException {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("visualizationId", id);
		request.put("sourceSvnUrl", sourceUrl);
		request.put("district", district);
		request.put("keepSeeds", keepSeeds);
		request.put("runs", runIds);
		return JSON.writeValueAsString(request) + "\n";
	}

	private static void validateId(String value, String description) {
		if (value == null || !value.matches("[A-Za-z0-9._-]+"))
			throw new IllegalArgumentException(description + " may contain only letters, digits, '.', '_' and '-'");
	}

	private static String stripTrailingSlashes(String value) {
		String result = value.strip();
		while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
		if (result.isEmpty()) throw new IllegalArgumentException("SVN URL must not be empty");
		return result;
	}

	private record RunManifest(List<String> runs) {
	}

	private record RunRequest(String runId, long seed, double gamma, int iterations, String masks) {
	}
}
