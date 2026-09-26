package org.matsim.episim.run.scenarios;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@value #FILE_NAME} of a scenario folder: city (the district attribute of the population), country, region, the
 * observed data for the viewer. Example: {@code Scenarios/Berlin/scenario.yaml}.
 */
public record ScenarioDescriptor(Path directory, String city, String country, String region, List<Observed> observed) {

	public static final String FILE_NAME = "scenario.yaml";
	public static final String CONFIG_FILE_NAME = "config.xml";

	/** Weekly series; {@code population} is needed for unit {@code count}. */
	public record Observed(String name, String file, String value, String unit, Integer population, String plot) {
	}

	private record Yaml(String city, String country, String region, List<Observed> observed) {
	}

	public static ScenarioDescriptor read(Path directory) {
		Path file = directory.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
			throw new IllegalArgumentException("No " + FILE_NAME + " in scenario folder " + directory.toAbsolutePath());

		try {
			Yaml yaml = new ObjectMapper(new YAMLFactory())
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(file.toFile(), Yaml.class);
			ScenarioDescriptor descriptor = new ScenarioDescriptor(directory, yaml.city(), yaml.country(), yaml.region(),
				yaml.observed() == null ? List.of() : List.copyOf(yaml.observed()));
			descriptor.validate();
			return descriptor;
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
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
