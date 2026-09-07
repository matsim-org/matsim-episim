package org.matsim.episim.run;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpisimContainerEntrypointTest {

	@Test
	void exposesBothContainerOperations() {
		assertEquals(0, EpisimContainerEntrypoint.execute("run", "--help"));
		assertEquals(0, EpisimContainerEntrypoint.execute("visualize", "--help"));
	}

	@Test
	void keepsTheOriginalOptionOnlyInvocationCompatible() {
		assertEquals(0, EpisimContainerEntrypoint.execute("--version"));
	}

	@Test
	void rejectsUnknownOperations() {
		assertEquals(CommandLine.ExitCode.USAGE, EpisimContainerEntrypoint.execute("unknown"));
	}
}
