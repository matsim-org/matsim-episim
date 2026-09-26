#!/usr/bin/env bash
# Sets up a machine for the influenza runs of this repository and runs them.
#
#   scripts/influenza.sh setup          once: Java, Maven, build, SVN credentials and working copy
#   scripts/influenza.sh build          after git pull
#   scripts/influenza.sh run [options]  e.g. nohup scripts/influenza.sh run --seeds 4 --infectiousness 0.3,0.35 > run.log 2>&1 &
#   scripts/influenza.sh forget         deletes the stored SVN password
#
# run options:
#   --scenario DIR          repeatable; default: every Scenarios/*/scenario.yaml (one dashboard, a tab per city)
#   --seeds N               seeds per parameter set (default 2, at most 10)
#   --infectiousness LIST   e.g. 0.30,0.35,0.40; values from 0.10 to 1.00 in steps of 0.05 (default: the config's value)
#   --tasks N               runs in parallel (default 1)
#   --memory SIZE           Java heap (default 24g); one 25 % run needs about 8 GB (Cologne) to 14 GB (Berlin)
#   --resume DATE/RUN       finish a failed run without simulating again (e.g. 2026-09-25/00002); give the same
#                           scenarios, seeds and infectiousness as the original run
#
# Settings are kept in ~/.episim (EPISIM_HOME): settings.env and svn-password (mode 600). setup asks for them, or takes
# SVN_USERNAME, SVN_FOLDER, OUTPUT_ROOT and SVN_PASSWORD_FILE from the environment.
# The viewer packages are committed to $SVN_ROOT/$SVN_FOLDER/<date>/<run>; the raw output stays in OUTPUT_ROOT.

set -euo pipefail

SVN_ROOT_DEFAULT="https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/episim/battery"
JAVA_VERSION=25
MAVEN_VERSION=3.9.9

EPISIM_HOME=${EPISIM_HOME:-$HOME/.episim}
SETTINGS="$EPISIM_HOME/settings.env"
PASSWORD_FILE_DEFAULT="$EPISIM_HOME/svn-password"
REPO=${EPISIM_REPO:-$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)}

die() { echo "error: $*" >&2; exit 1; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

load_settings() {
	# shellcheck source=/dev/null
	[[ -f "$SETTINGS" ]] && source "$SETTINGS"
	SVN_ROOT=${SVN_ROOT:-$SVN_ROOT_DEFAULT}
	SVN_PASSWORD_FILE=${SVN_PASSWORD_FILE:-$PASSWORD_FILE_DEFAULT}
}

java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print v[1]; exit }'; }

ensure_java() {
	if command -v java >/dev/null && (( $(java_major java || true) + 0 >= JAVA_VERSION )) 2> /dev/null; then
		JAVA=$(command -v java)
	elif [[ -x "$EPISIM_HOME/jdk/bin/java" ]]; then
		JAVA="$EPISIM_HOME/jdk/bin/java"
	else
		[[ $(uname -s) == Linux ]] || die "Java $JAVA_VERSION not found; the download is for Linux only, put it on the PATH"
		local arch
		case $(uname -m) in
			x86_64) arch=x64 ;;
			aarch64 | arm64) arch=aarch64 ;;
			*) die "no Java $JAVA_VERSION found and no download for $(uname -m); put it on the PATH" ;;
		esac
		log "downloading Java $JAVA_VERSION (Eclipse Temurin) to $EPISIM_HOME/jdk"
		mkdir -p "$EPISIM_HOME/jdk"
		curl -fsSL "https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga/linux/$arch/jdk/hotspot/normal/eclipse" \
			| tar -xz -C "$EPISIM_HOME/jdk" --strip-components=1
		JAVA="$EPISIM_HOME/jdk/bin/java"
	fi
	JAVA_HOME=$(cd "$(dirname "$(readlink -f "$JAVA")")/.." && pwd)
	export JAVA_HOME
}

ensure_maven() {
	if command -v mvn >/dev/null && mvn -v 2>/dev/null | head -1 | grep -qE 'Maven 3\.(9|[1-9][0-9])|Maven [4-9]'; then
		MVN=$(command -v mvn)
	else
		if [[ ! -x "$EPISIM_HOME/maven/bin/mvn" ]]; then
			log "downloading Maven $MAVEN_VERSION to $EPISIM_HOME/maven"
			mkdir -p "$EPISIM_HOME/maven"
			curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" \
				| tar -xz -C "$EPISIM_HOME/maven" --strip-components=1
		fi
		MVN="$EPISIM_HOME/maven/bin/mvn"
	fi
}

svn_auth() {
	[[ -r "$SVN_PASSWORD_FILE" ]] || die "no SVN password file $SVN_PASSWORD_FILE; run setup"
	svn --non-interactive --username "$SVN_USERNAME" --no-auth-cache --password-from-stdin "$@" < "$SVN_PASSWORD_FILE"
}

ask() { # ask VAR "question" default
	local current=${!1:-$3} answer
	read -r -p "$2 [$current]: " answer
	printf -v "$1" '%s' "${answer:-$current}"
}

build() {
	ensure_java
	ensure_maven
	log "building matsim-episim (the first build downloads several hundred MB of dependencies)"
	# Maven 3.9 on Java 25 warns about jansi and Unsafe; the timeouts keep an unreachable repository from blocking the build
	export MAVEN_OPTS="${MAVEN_OPTS:-} --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
	(cd "$REPO" && "$MVN" -B -DskipTests -Daether.connector.connectTimeout=30000 -Daether.connector.requestTimeout=120000 \
		package | grep --line-buffered -E '^\[(INFO|WARNING|ERROR)\] (Downloading from|BUILD|Total time)|ERROR')
	log "built $(ls -t "$REPO"/matsim-episim-*.jar | head -1)"
}

setup() {
	mkdir -p "$EPISIM_HOME"
	chmod 700 "$EPISIM_HOME"
	load_settings
	command -v svn >/dev/null || die "svn is not installed"
	svn --version --quiet | awk -F. '{ exit !($1 > 1 || $2 >= 10) }' || die "svn 1.10 or newer is needed (--password-from-stdin)"

	ask SVN_USERNAME "SVN user name" "${SVN_USERNAME:-$USER}"
	ask SVN_FOLDER "folder below $SVN_ROOT for the results" "${SVN_FOLDER:-$SVN_USERNAME}"
	ask OUTPUT_ROOT "local working copy (raw output goes here too)" "${OUTPUT_ROOT:-$HOME/episim-battery/$SVN_FOLDER}"

	if [[ ! -s "$SVN_PASSWORD_FILE" ]]; then
		local password
		read -r -s -p "SVN password for $SVN_USERNAME (stored in $SVN_PASSWORD_FILE, mode 600): " password
		echo
		(umask 077 && printf '%s\n' "$password" > "$SVN_PASSWORD_FILE")
	fi

	(umask 077 && cat > "$SETTINGS" <<-EOF
		SVN_ROOT='$SVN_ROOT'
		SVN_USERNAME='$SVN_USERNAME'
		SVN_FOLDER='$SVN_FOLDER'
		SVN_PASSWORD_FILE='$SVN_PASSWORD_FILE'
		OUTPUT_ROOT='$OUTPUT_ROOT'
	EOF
	)

	log "checking SVN access to $SVN_ROOT/$SVN_FOLDER"
	svn_auth info "$SVN_ROOT/$SVN_FOLDER" > /dev/null || die "cannot read $SVN_ROOT/$SVN_FOLDER with these credentials"
	if [[ ! -d "$OUTPUT_ROOT/.svn" ]]; then
		mkdir -p "$(dirname "$OUTPUT_ROOT")"
		svn_auth checkout --quiet --depth empty "$SVN_ROOT/$SVN_FOLDER" "$OUTPUT_ROOT"
	fi

	build
	log "setup done; settings in $SETTINGS"
}

run() {
	load_settings
	[[ -n "${SVN_USERNAME:-}" && -n "${OUTPUT_ROOT:-}" ]] || die "not set up; run: $0 setup"

	local memory=24g args=() scenarios=()
	while (( $# )); do
		case $1 in
			--scenario | --seeds | --infectiousness | --tasks | --memory | --resume) [[ $# -ge 2 ]] || die "option $1 needs a value" ;;
			*) die "unknown option $1" ;;
		esac
		case $1 in
			--scenario) scenarios+=(--scenario "$2") ;;
			--seeds | --infectiousness | --tasks | --resume) args+=("$1" "$2") ;;
			--memory) memory=$2 ;;
		esac
		shift 2
	done
	if (( ${#scenarios[@]} == 0 )); then
		local descriptor
		for descriptor in "$REPO"/Scenarios/*/scenario.yaml; do
			scenarios+=(--scenario "Scenarios/$(basename "$(dirname "$descriptor")")")
		done
	fi

	ensure_java
	local jar
	jar=$(ls -t "$REPO"/matsim-episim-*.jar 2> /dev/null | head -1) || true
	[[ -n "$jar" ]] || die "no matsim-episim jar in $REPO; run: $0 build"

	# let the working copy know today's run folders, so the batch picks a free run number
	svn_auth update --quiet --set-depth immediates "$OUTPUT_ROOT"
	local today
	today=$(date +%F)
	[[ -d "$OUTPUT_ROOT/$today" ]] && svn_auth update --quiet --set-depth immediates "$OUTPUT_ROOT/$today"

	log "RunInfluenza ${scenarios[*]} ${args[*]:-} -> $SVN_ROOT/$SVN_FOLDER"
	cd "$REPO"
	EPISIM_OUTPUT="$OUTPUT_ROOT" SVN_USERNAME="$SVN_USERNAME" SVN_PASSWORD_FILE="$SVN_PASSWORD_FILE" \
		"$JAVA" "-Xmx$memory" -cp "$jar" org.matsim.episim.run.batch.RunInfluenza "${scenarios[@]}" ${args[@]+"${args[@]}"}
}

case ${1:-} in
	setup) setup ;;
	build) load_settings; build ;;
	run) shift; run "$@" ;;
	forget) load_settings; rm -f "$SVN_PASSWORD_FILE"; log "removed $SVN_PASSWORD_FILE" ;;
	*) sed -n '2,20p' "$0"; exit 1 ;;
esac
