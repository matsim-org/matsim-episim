package org.matsim.episim.run;

import org.junit.jupiter.api.Test;
import org.matsim.run.BatchOutputPacker;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisimVisualizationRunnerTest {

	@Test
	void exposesSvnAggregationOptions() {
		CommandLine commandLine = new CommandLine(new EpisimVisualizationRunner());

		assertTrue(commandLine.getCommandSpec().optionsMap().keySet().containsAll(java.util.Set.of(
			"--runs-file", "--source-svn-url", "--target-svn-url", "--visualization-id",
			"--district", "--keep-seeds", "--work-root",
			"--svn-username-file", "--svn-password-file")));
	}

	@Test
	void readsAnOrderedUniqueManifest() throws Exception {
		Path manifest = Files.createTempFile("runs", ".json");
		Files.writeString(manifest, "{\"runs\":[\"run-2\",\"run-1\"]}");

		assertEquals(List.of("run-2", "run-1"), EpisimVisualizationRunner.readManifest(manifest));

		Files.writeString(manifest, "{\"runs\":[\"run-1\",\"run-1\"]}");
		assertThrows(IllegalArgumentException.class, () -> EpisimVisualizationRunner.readManifest(manifest));
	}

	@Test
	void assemblesDownloadedPublicationsIntoBatchLayout() throws Exception {
		Path root = Files.createTempDirectory("visualization-runner");
		Path downloads = Files.createDirectory(root.resolve("downloads"));
		writePublication(downloads, "seed_1-gamma_0p8-masks_yes", 1, 0.8);
		writePublication(downloads, "seed_2-gamma_0p8-masks_yes", 2, 0.8);
		Path batch = Files.createDirectory(root.resolve("batch"));

		EpisimVisualizationRunner.assembleBatch(downloads, batch,
			List.of("seed_1-gamma_0p8-masks_yes", "seed_2-gamma_0p8-masks_yes"));

		assertTrue(Files.readString(batch.resolve("_info.txt")).contains(
			"seed_1-gamma_0p8-masks_yes;seed_1-gamma_0p8-masks_yes;1;0.8;10;yes"));
		assertTrue(Files.isRegularFile(batch.resolve(
			"seed_1-gamma_0p8-masks_yes/seed_1-gamma_0p8-masks_yes.infections.txt")));
		assertTrue(Files.isRegularFile(batch.resolve(
			"seed_1-gamma_0p8-masks_yes/seed_1-gamma_0p8-masks_yes.config.xml")));
		assertTrue(Files.readString(batch.resolve("metadata.yaml")).contains("viewerVersion: 2"));

		Path viewer = root.resolve("viewer");
		new BatchOutputPacker(batch, viewer, "Köln", false).pack();
		assertTrue(Files.isRegularFile(viewer.resolve("summaries/0.zip")));
	}

	private static void writePublication(Path downloads, String runId, long seed, double gamma) throws IOException {
		Path publication = Files.createDirectory(downloads.resolve(runId));
		Files.writeString(publication.resolve("request.json"), """
			{
			  "runId": "%s",
			  "seed": %d,
			  "gamma": %s,
			  "iterations": 10,
			  "masks": "yes"
			}
			""".formatted(runId, seed, gamma));
		Files.writeString(publication.resolve("config.xml"), "<config/>");
		Files.createFile(publication.resolve("_SUCCESS"));
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(publication.resolve("results.zip")),
			StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("infections.txt"));
			zip.write(("time\tday\tdate\tnShowingSymptomsCumulative\tdistrict\n"
				+ "86400\t1\t2021-01-01\t10\tKöln\n").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}
}
