#!/usr/bin/env bash
# Sets up a machine for the influenza runs of this repository and runs them.
#
#   scripts/influenza.sh setup          once: SVN credentials and working copy, plus Java, Maven and the build without a container engine
#   scripts/influenza.sh build          after git pull (without a container engine, or for run --native)
#   scripts/influenza.sh run [options]  e.g. nohup scripts/influenza.sh run --seeds 4 --infectiousness 0.3,0.35 > run.log 2>&1 &
#   scripts/influenza.sh forget         deletes the stored SVN password
#
# run options:
#   --scenario DIR          repeatable; default: every Scenarios/*/scenario.yaml (one dashboard, a tab per city)
#   --seeds N               seeds per parameter set (default 2, at most 10)
#   --infectiousness LIST   e.g. 0.30,0.35,0.40; values from 0.10 to 1.00 in steps of 0.05 (default: the config's value)
#   --tasks N               runs in parallel (default 1)
#   --task-threads N        threads per run, at least 2 (default: the cores divided by --tasks); changes the results
#   --memory SIZE           Java heap (default 24g); one 25 % run needs about 8 GB (Cologne) to 14 GB (Berlin)
#   --resume DATE/RUN       finish a failed run without simulating again (e.g. 2026-09-25/00002); give the same
#                           scenarios, seeds and infectiousness as the original run
#   --image REF             container image (default: docker.io/jarodocks/matsim-episim:sha-<HEAD>, built by GitHub Actions;
#                           give a ...@sha256:<digest> to repeat a run exactly)
#   --native                run the jar built here instead of the container
#
# With podman (CONTAINER_ENGINE) installed, run starts the image: Java, the application and svn come from the
# image, the working copy OUTPUT_ROOT is mounted as /output and the SVN password file read-only. A --scenario folder
# outside Scenarios/ of this repository is mounted into the container, the others are the image's own. The image
# digest and git commit of the run are printed and written to $OUTPUT_ROOT/../run-images.log.
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

CONTAINER_ENGINE=${CONTAINER_ENGINE:-$(command -v podman || true)}
IMAGE_REPO=${EPISIM_IMAGE_REPO:-docker.io/jarodocks/matsim-episim}
IMAGE_REF=${EPISIM_IMAGE_REF:-}
IMAGE_DIGEST=""
NATIVE=false
[[ -n "$CONTAINER_ENGINE" ]] || NATIVE=true

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

ensure_svn() {
	[[ -x "$EPISIM_HOME/svn/bin/svn" ]] && PATH="$EPISIM_HOME/svn/bin:$PATH"
	if ! command -v svn >/dev/null; then
		local platform
		case "$(uname -s)-$(uname -m)" in
			Linux-x86_64) platform=linux-64 ;;
			Linux-aarch64) platform=linux-aarch64 ;;
			Darwin-arm64) platform=osx-arm64 ;;
			Darwin-x86_64) platform=osx-64 ;;
			*) die "svn is not installed and cannot be installed for $(uname -sm)" ;;
		esac
		log "installing Subversion (conda-forge, via micromamba) to $EPISIM_HOME/svn"
		mkdir -p "$EPISIM_HOME/micromamba"
		curl -fsSL "https://micro.mamba.pm/api/micromamba/$platform/latest" | tar -xj -C "$EPISIM_HOME/micromamba" bin/micromamba
		MAMBA_ROOT_PREFIX="$EPISIM_HOME/micromamba" "$EPISIM_HOME/micromamba/bin/micromamba" create -y -q \
			-p "$EPISIM_HOME/svn" -c conda-forge subversion > /dev/null
		PATH="$EPISIM_HOME/svn/bin:$PATH"
	fi
	# the batch commits with svn too
	export PATH
	svn --version --quiet | awk -F. '{ exit !($1 > 1 || $2 >= 10) }' || die "svn 1.10 or newer is needed (--password-from-stdin)"
}

# The container image to use: --image, else the one GitHub Actions built for this checkout. Pulled if not present.
ensure_image() {
	[[ -z "$IMAGE_DIGEST" ]] || return 0
	[[ -n "$IMAGE_REF" ]] || IMAGE_REF="$IMAGE_REPO:sha-$(git -C "$REPO" rev-parse --short=7 HEAD)"
	if ! "$CONTAINER_ENGINE" image exists "$IMAGE_REF" 2> /dev/null; then
		log "pulling $IMAGE_REF"
		"$CONTAINER_ENGINE" pull --quiet "$IMAGE_REF" > /dev/null \
			|| die "cannot pull $IMAGE_REF; has GitHub Actions built this commit? Otherwise give --image"
	fi
	IMAGE_DIGEST=$("$CONTAINER_ENGINE" image inspect --format '{{index .RepoDigests 0}}' "$IMAGE_REF" 2> /dev/null || true)
	[[ -n "$IMAGE_DIGEST" ]] || IMAGE_DIGEST=$IMAGE_REF
}

# Subversion comes from the image; only a native run needs it installed here.
prepare_svn() {
	if [[ $NATIVE == true ]]; then ensure_svn; else ensure_image; fi
}

svn_auth() {
	[[ -r "$SVN_PASSWORD_FILE" ]] || die "no SVN password file $SVN_PASSWORD_FILE; run setup"
	if [[ $NATIVE == true ]]; then
		svn --non-interactive --username "$SVN_USERNAME" --no-auth-cache --password-from-stdin "$@" < "$SVN_PASSWORD_FILE"
	else
		ensure_image
		# the working copy is mounted at its own path, so the arguments are the same as natively
		mkdir -p "$OUTPUT_ROOT"
		"$CONTAINER_ENGINE" run --rm -i --userns=keep-id:uid=10001,gid=10001 --security-opt label=disable \
			-v "$OUTPUT_ROOT:$OUTPUT_ROOT" "$IMAGE_REF" sh -c 'exec svn "$@"' svn \
			--non-interactive --username "$SVN_USERNAME" --no-auth-cache --password-from-stdin "$@" < "$SVN_PASSWORD_FILE"
	fi
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
	prepare_svn

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

	if [[ $NATIVE == true ]]; then
		build
	else
		log "using $CONTAINER_ENGINE for runs; run --native needs: $0 build"
	fi
	log "setup done; settings in $SETTINGS"
}

# run_container MEMORY --scenario DIR ... -- ARGS...
run_container() {
	local memory=$1
	shift
	local scenarios=() args=()
	while (( $# )) && [[ $1 != -- ]]; do
		[[ $1 == --scenario ]] && scenarios+=("$2")
		shift 2
	done
	shift
	args=("$@")

	ensure_image
	local image=$IMAGE_REF digest=$IMAGE_DIGEST commit
	log "using $digest"
	commit=$("$CONTAINER_ENGINE" run --rm "$image" version | sed -n 's/^gitCommit=//p')

	# scenarios of this repository are the image's own; other folders are mounted
	local mounts=() container_args=() dir host candidate
	for dir in "${scenarios[@]}"; do
		candidate=$dir
		[[ $dir == /* ]] || candidate="$REPO/$dir"
		if [[ -d "$candidate" ]]; then
			host=$(cd "$candidate" && pwd)
			if [[ $host == "$REPO"/Scenarios/* ]]; then
				container_args+=(--scenario "Scenarios/$(basename "$host")")
			else
				mounts+=(-v "$host:/scenarios/$(basename "$host"):ro")
				container_args+=(--scenario "/scenarios/$(basename "$host")")
			fi
		else
			container_args+=(--scenario "$dir")
		fi
	done

	svn_auth update --quiet --set-depth immediates "$OUTPUT_ROOT"
	local today
	today=$(date +%F)
	[[ -d "$OUTPUT_ROOT/$today" ]] && svn_auth update --quiet --set-depth immediates "$OUTPUT_ROOT/$today"

	printf '%s\t%s\t%s\n' "$digest" "$commit" "$(date -u +%FT%TZ)" >> "$(dirname "$OUTPUT_ROOT")/run-images.log"
	log "RunInfluenza in $digest (commit ${commit:-unknown}): ${container_args[*]} ${args[*]:-} -> $SVN_ROOT/$SVN_FOLDER"
	# the user of the host owns the files in /output: keep-id maps it to the uid of the image
	"$CONTAINER_ENGINE" run --rm --userns=keep-id:uid=10001,gid=10001 --security-opt label=disable \
		-v "$OUTPUT_ROOT:/output" \
		-v "$SVN_PASSWORD_FILE:/run/secrets/svn-password:ro" \
		${mounts[@]+"${mounts[@]}"} \
		-e HOSTNAME="$(hostname)" \
		-e SVN_USERNAME="$SVN_USERNAME" -e SVN_PASSWORD_FILE=/run/secrets/svn-password \
		-e EPISIM_IMAGE="$digest" \
		-e JAVA_TOOL_OPTIONS="-Xmx$memory -Djava.awt.headless=true" \
		"$image" influenza "${container_args[@]}" ${args[@]+"${args[@]}"}
}

run() {
	load_settings
	[[ -n "${SVN_USERNAME:-}" && -n "${OUTPUT_ROOT:-}" ]] || die "not set up; run: $0 setup"

	local memory=24g args=() scenarios=()
	while (( $# )); do
		case $1 in
			--native) NATIVE=true; shift; continue ;;
			--scenario | --seeds | --infectiousness | --tasks | --task-threads | --memory | --resume | --image) [[ $# -ge 2 ]] || die "option $1 needs a value" ;;
			*) die "unknown option $1" ;;
		esac
		case $1 in
			--scenario) scenarios+=(--scenario "$2") ;;
			--seeds | --infectiousness | --tasks | --task-threads | --resume) args+=("$1" "$2") ;;
			--memory) memory=$2 ;;
			--image) IMAGE_REF=$2 ;;
		esac
		shift 2
	done
	if (( ${#scenarios[@]} == 0 )); then
		local descriptor
		for descriptor in "$REPO"/Scenarios/*/scenario.yaml; do
			scenarios+=(--scenario "Scenarios/$(basename "$(dirname "$descriptor")")")
		done
	fi

	if [[ $NATIVE == false ]]; then
		run_container "$memory" "${scenarios[@]}" -- ${args[@]+"${args[@]}"}
		return
	fi

	ensure_java
	ensure_svn
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
