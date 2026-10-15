package org.matsim.episim.run.modules;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Modules;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.EpisimModule;
import org.matsim.episim.EpisimRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshots on the Cologne multi-season influenza scenario ({@code Scenarios/Cologne-multiseason}, as configured).
 *
 * <ol>
 *   <li>one calculation of 30 days gives the reference result;</li>
 *   <li>three calculations of 10 days follow each other: the first starts from scratch, the second and the third start
 *   from the snapshot the previous one wrote at its end;</li>
 *   <li>the result of the third calculation (which carries the output of the earlier ones) equals the reference.</li>
 * </ol>
 *
 * <p>The scenario's snapshot interval of 100 days is shortened to 10, so that a snapshot is written at the end of each
 * calculation. Run it from the repo root; it reads the Cologne inputs from the VSP SVN and simulates 60 days in total.</p>
 */
public class InfluenzaScenarioSnapshotTest {

	private static final Path SCENARIO = Path.of("Scenarios/Cologne-multiseason");

	private static final int DAYS = 10;

	/** Not compared: archives, configs, timings, and episodes open at a snapshot (restored without infector, other order). */
	private static final List<String> NOT_COMPARED = List.of(".zip", ".xml", ".gz", ".tar", "cputime.tsv", "infectionEpisodes.tsv");

	@TempDir
	Path root;

	@Test
	public void threeCalculationsFromSnapshotsEqualOneOf30Days() throws IOException {

		Path reference = root.resolve("reference");
		calculate(reference, 3 * DAYS, null);

		Path first = root.resolve("first");
		calculate(first, DAYS, null);

		Path second = root.resolve("second");
		calculate(second, 2 * DAYS, snapshot(first, DAYS));

		Path third = root.resolve("third");
		calculate(third, 3 * DAYS, snapshot(second, 2 * DAYS));

		assertSameResults(reference, third);
	}

	/**
	 * Calculates up to the given day (a start from a snapshot continues at the snapshot's day), writing a snapshot
	 * every {@value #DAYS} days into {@code output}.
	 */
	private static void calculate(Path output, int untilDay, Path snapshot) throws IOException {

		OutputDirectoryLogging.catchLogEntries();
		Files.createDirectories(output);

		Injector injector = Guice.createInjector(Modules.override(new EpisimModule()).with(new InfluenzaScenario(SCENARIO)));

		Config config = injector.getInstance(Config.class);
		config.controller().setOutputDirectory(output.toString());

		EpisimConfigGroup episimConfig = injector.getInstance(EpisimConfigGroup.class);
		episimConfig.setSnapshotInterval(DAYS);
		if (snapshot != null)
			episimConfig.setStartFromSnapshot(snapshot.toString());

		injector.getInstance(EpisimRunner.class).run(untilDay);
	}

	/** The snapshot written at the given day; its file name carries the date as well. */
	private static Path snapshot(Path output, int day) throws IOException {
		try (Stream<Path> files = Files.list(output)) {
			return files.filter(f -> f.getFileName().toString().startsWith(String.format("episim-snapshot-%03d-", day)))
					.findFirst().orElseThrow(() -> new AssertionError("no snapshot of day " + day + " in " + output));
		}
	}

	private static void assertSameResults(Path expected, Path actual) throws IOException {

		List<Path> files;
		try (Stream<Path> list = Files.list(expected)) {
			files = list.filter(Files::isRegularFile)
					.filter(f -> NOT_COMPARED.stream().noneMatch(s -> f.getFileName().toString().endsWith(s)))
					.toList();
		}

		// guard against comparing nothing
		assertThat(files).anyMatch(f -> f.getFileName().toString().endsWith("infectionEvents.txt"));

		// all differing files are reported, not only the first
		SoftAssertions softly = new SoftAssertions();
		for (Path file : files)
			softly.assertThat(actual.resolve(file.getFileName()).toFile()).as(file.getFileName().toString())
					.hasSameTextualContentAs(file.toFile());
		softly.assertAll();
	}
}
