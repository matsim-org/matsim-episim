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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Publishes one immutable run directory as a single SVN revision. */
final class SvnResultPublisher {

	private final String executable;
	private final String baseUrl;
	private final String username;
	private final String password;

	SvnResultPublisher(String executable, String baseUrl, Path usernameFile, Path passwordFile) throws IOException {
		this.executable = executable;
		this.baseUrl = stripTrailingSlashes(baseUrl);
		this.username = stripLineEnding(Files.readString(usernameFile, StandardCharsets.UTF_8));
		this.password = stripLineEnding(Files.readString(passwordFile, StandardCharsets.UTF_8));

		if (this.username.isBlank()) throw new IllegalArgumentException("SVN username must not be blank");
		if (this.password.isEmpty()) throw new IllegalArgumentException("SVN password must not be empty");
	}

	boolean isAlreadyPublished(String runId, String expectedRequest) throws IOException, InterruptedException {
		CommandResult listing = execute("list", baseUrl);
		if (listing.exitCode() != 0)
			throw failure("Cannot list SVN publication directory " + baseUrl, listing);

		boolean exists = listing.output().lines().map(String::strip)
			.anyMatch(line -> line.equals(runId + "/"));
		if (!exists) return false;

		CommandResult request = execute("cat", targetUrl(runId) + "/request.json");
		if (request.exitCode() != 0)
			throw failure("SVN run directory exists but request.json cannot be read", request);
		if (!request.output().strip().equals(expectedRequest.strip()))
			throw new IllegalStateException("SVN run directory already exists with a different request: " + targetUrl(runId));
		return true;
	}

	void publish(String runId, Path publicationDirectory, String expectedRequest)
		throws IOException, InterruptedException {
		CommandResult result = execute(
			"import", publicationDirectory.toAbsolutePath().toString(), targetUrl(runId),
			"--message", "EPISim result: " + runId);
		if (result.exitCode() == 0) return;

		// A Pod may have been restarted immediately after a successful commit.
		if (isAlreadyPublished(runId, expectedRequest)) return;
		throw failure("SVN import failed for " + targetUrl(runId), result);
	}

	void export(String sourceUrl, Path target) throws IOException, InterruptedException {
		Path absoluteTarget = target.toAbsolutePath();
		if (absoluteTarget.getParent() != null) Files.createDirectories(absoluteTarget.getParent());
		CommandResult result = execute("export", sourceUrl, absoluteTarget.toString(), "--force");
		if (result.exitCode() != 0)
			throw failure("Cannot export SVN path " + sourceUrl, result);
	}

	String targetUrl(String runId) {
		return baseUrl + "/" + runId;
	}

	private CommandResult execute(String... operationArguments) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(executable);
		command.addAll(List.of(operationArguments));
		command.add("--username");
		command.add(username);
		command.add("--password-from-stdin");
		command.add("--no-auth-cache");
		command.add("--non-interactive");

		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		try (OutputStream input = process.getOutputStream()) {
			input.write(password.getBytes(StandardCharsets.UTF_8));
			input.write('\n');
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new CommandResult(process.waitFor(), output);
	}

	private static IllegalStateException failure(String message, CommandResult result) {
		return new IllegalStateException(message + " (exit " + result.exitCode() + "): " + result.output().strip());
	}

	private static String stripTrailingSlashes(String value) {
		String result = value.strip();
		while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
		if (result.isEmpty()) throw new IllegalArgumentException("SVN URL must not be empty");
		return result;
	}

	private static String stripLineEnding(String value) {
		int end = value.length();
		while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
		return value.substring(0, end);
	}

	private record CommandResult(int exitCode, String output) {
	}
}
