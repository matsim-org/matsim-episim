package org.matsim.episim.run;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvnResultPublisherTest {

	@Test
	void readsPasswordFromStdinWithoutPuttingItInArguments() throws Exception {
		Path directory = Files.createTempDirectory("svn-publisher");
		Path commandLog = directory.resolve("command.log");
		Path executable = directory.resolve("svn-fake");
		Files.writeString(executable, """
			#!/bin/sh
			printf '%%s\\n' "$@" >> '%s'
			IFS= read -r supplied_password
			[ "$supplied_password" = "top-secret" ] || exit 9
			exit 0
			""".formatted(commandLog));
		executable.toFile().setExecutable(true);

		Path username = directory.resolve("username");
		Path password = directory.resolve("password");
		Files.writeString(username, "episim-user\n");
		Files.writeString(password, "top-secret\n");
		Path publication = Files.createDirectory(directory.resolve("publication"));
		Files.writeString(publication.resolve("request.json"), "{}\n");

		SvnResultPublisher publisher = new SvnResultPublisher(
			executable.toString(), "https://svn.example/results/", username, password);

		assertFalse(publisher.isAlreadyPublished("run-1", "{}\n"));
		publisher.publish("run-1", publication, "{}\n");

		String arguments = Files.readString(commandLog);
		assertTrue(arguments.contains("--password-from-stdin"));
		assertTrue(arguments.contains("--no-auth-cache"));
		assertTrue(arguments.contains("episim-user"));
		assertFalse(arguments.contains("top-secret"));
	}
}
