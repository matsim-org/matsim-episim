#!/bin/sh
# Entrypoint of the matsim-episim image: first argument selects what runs, the rest goes to it.
set -eu

JAR=/opt/episim/episim.jar
PKG=org.matsim.episim.run

usage() {
	cat <<-USAGE
	usage: <mode> [options]
	  influenza [options]   influenza season (see --scenario, --seeds, --infectiousness, --tasks, --resume)
	  influenza-gamma [options]  multi-season influenza, calibrated by the common factor (--scenario, --seeds, --gamma, --iterations, --tasks)
	  run <class> [args]    any main class of the jar
	  version               git commit and build date of this image
	  sh                    a shell
	Output goes to \$EPISIM_OUTPUT (default /output); an SVN working copy there is committed to when
	SVN_USERNAME and SVN_PASSWORD_FILE are set.
	USAGE
}

mode=${1:-help}
[ $# -gt 0 ] && shift

case $mode in
	influenza) exec java -cp "$JAR" "$PKG.batch.RunInfluenza" "$@" ;;
	influenza-gamma) exec java -cp "$JAR" "$PKG.batch.RunInfluenzaGamma" "$@" ;;
	run) exec java -cp "$JAR" "$@" ;;
	version) cat /opt/episim/BUILD_INFO ;;
	sh | bash) exec "$mode" "$@" ;;
	help | -h | --help) usage ;;
	*) echo "unknown mode: $mode" >&2; usage >&2; exit 2 ;;
esac
