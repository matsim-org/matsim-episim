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

import picocli.CommandLine;

import java.util.Arrays;

/** Selects the operation performed by the shared container image. */
public final class EpisimContainerEntrypoint {

	private EpisimContainerEntrypoint() {
	}

	public static void main(String[] args) {
		System.exit(execute(args));
	}

	static int execute(String... args) {
		if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
			printUsage();
			return 0;
		}

		String operation = args[0];
		String[] operationArgs = Arrays.copyOfRange(args, 1, args.length);
		return switch (operation) {
			case "run" -> new CommandLine(new EpisimVariantRunner()).execute(operationArgs);
			case "visualize" -> new CommandLine(new EpisimVisualizationRunner()).execute(operationArgs);
			default -> operation.startsWith("-")
				? new CommandLine(new EpisimVariantRunner()).execute(args)
				: unknownOperation(operation);
		};
	}

	private static int unknownOperation(String operation) {
		System.err.println("Unknown operation: " + operation);
		printUsage();
		return CommandLine.ExitCode.USAGE;
	}

	private static void printUsage() {
		System.out.println("""
			Usage: episim-runner <operation> [options]

			Operations:
			  run        Run and publish one seed/gamma variant
			  visualize  Collect listed SVN runs and publish an Episim viewer package

			Use '<operation> --help' to see operation-specific options.
			""");
	}
}
