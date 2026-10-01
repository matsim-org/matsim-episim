package org.matsim.episim.run.scenarios;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * {@value #FILE_NAME} of a scenario folder: city (the district attribute of the population), country, region, the first
 * and the last day of the run, the observed data for the viewer. Example: {@code Scenarios/Berlin/scenario.yaml}.
 *
 * <p>{@code start} and {@code end} are optional; a scenario that gives neither runs the single season
 * {@link InfluenzaParameterisation#SEASON_START} to {@link InfluenzaParameterisation#SEASON_END}.</p>
 */
public record ScenarioDescriptor(Path directory, String city, String country, String region, LocalDate start, LocalDate end,
								 List<Observed> observed) {

	public static final String FILE_NAME = "scenario.yaml";
	public static final String CONFIG_FILE_NAME = "config.xml";

	/** Weekly series; {@code population} is needed for unit {@code count}. */
	public record Observed(String name, String file, String value, String unit, Integer population, String plot) {
	}

	private record Yaml(String city, String country, String region, String start, String end, List<Observed> observed) {
	}

	public static ScenarioDescriptor read(Path directory) {
		Path file = directory.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
			throw new IllegalArgumentException("No " + FILE_NAME + " in scenario folder " + directory.toAbsolutePath());

		try {
			Yaml yaml = new ObjectMapper(new YAMLFactory())
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(file.toFile(), Yaml.class);
			if ((yaml.start() == null) != (yaml.end() == null))
				throw new IllegalArgumentException("'start' and 'end' have to be given together in " + file);
			ScenarioDescriptor descriptor = new ScenarioDescriptor(directory, yaml.city(), yaml.country(), yaml.region(),
				yaml.start() == null ? InfluenzaParameterisation.SEASON_START : LocalDate.parse(yaml.start()),
				yaml.end() == null ? InfluenzaParameterisation.SEASON_END : LocalDate.parse(yaml.end()),
				yaml.observed() == null ? List.of() : List.copyOf(yaml.observed()));
			descriptor.validate();
			return descriptor;
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	/** Number of days of the run, the first and the last day included. */
	public int iterations() {
		return (int) (ChronoUnit.DAYS.between(start, end) + 1);
	}

	public Path config() {
		return directory.resolve(CONFIG_FILE_NAME);
	}

	public Path observedFile(Observed observed) {
		return directory.resolve(observed.file());
	}

	private void validate() {
		for (String[] field : new String[][]{{"city", city}, {"country", country}, {"region", region}})
			if (field[1] == null || field[1].isBlank())
				throw new IllegalArgumentException("'" + field[0] + "' missing in " + directory.resolve(FILE_NAME));

		if (end.isBefore(start))
			throw new IllegalArgumentException("'end' " + end + " is before 'start' " + start + " in " + directory.resolve(FILE_NAME));

		for (Observed o : observed) {
			if (!Files.isRegularFile(observedFile(o)))
				throw new IllegalArgumentException("Observed data '" + o.name() + "' not found: " + observedFile(o));
			if (!o.unit().equals("count") && !o.unit().equals("per100k"))
				throw new IllegalArgumentException("Unit of '" + o.name() + "' must be count or per100k, was " + o.unit());
			if (o.unit().equals("count") && o.population() == null)
				throw new IllegalArgumentException("'" + o.name() + "' is a count and needs 'population'");
		}
	}

}
