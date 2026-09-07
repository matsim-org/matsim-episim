package org.matsim.episim.run;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisimVariantRunnerTest {

	@Test
	void exposesRunnerOptions() {
		CommandLine commandLine = new CommandLine(new EpisimVariantRunner());

		assertEquals("run-variant", commandLine.getCommandName());
		assertTrue(commandLine.getCommandSpec().optionsMap().keySet().containsAll(java.util.Set.of(
			"--seed", "--gamma", "--iterations", "--run-id", "--work-root",
			"--svn-url", "--svn-username-file", "--svn-password-file")));
	}

	@Test
	void createsStableDefaultRunId() {
		assertEquals("seed_4711-gamma_0p8-masks_yes", EpisimVariantRunner.defaultRunId(
			4711, 0.8, org.matsim.episim.run.modules.SnzCologneOpenProductionScenario.Masks.yes));
	}

	@Test
	void archivesOutputTree() throws Exception {
		Path source = Files.createTempDirectory("episim-output");
		Files.createDirectories(source.resolve("nested"));
		Files.writeString(source.resolve("result.txt"), "result");
		Files.writeString(source.resolve("nested/details.txt"), "details");
		Path archive = Files.createTempFile("episim-result", ".zip");

		EpisimVariantRunner.createZip(source, archive);

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			assertEquals("result", new String(zip.getInputStream(zip.getEntry("result.txt")).readAllBytes()));
			assertEquals("details", new String(zip.getInputStream(zip.getEntry("nested/details.txt")).readAllBytes()));
		}
	}
}
