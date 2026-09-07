#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Run a seed × gamma × masks matrix, create runs.json, and publish the visualization.

Usage:
  publish-visualization-matrix.sh \
    --seeds 4711,4712 \
    --gammas 0.8,1.0 \
    --masks yes,no \
    --source-svn-url URL \
    --target-svn-url URL \
    --visualization-id ID [options]

Required options:
  --seeds LIST              Comma-separated integer seeds.
  --gammas LIST             Comma-separated positive gamma values.
  --masks LIST              Comma-separated masks values: yes,no.
  --source-svn-url URL      SVN directory containing per-run directories.
  --target-svn-url URL      Existing SVN directory for visualizations.
  --visualization-id ID     New immutable visualization directory name.

Optional:
  --image IMAGE             Container image (default: episim-runner:poc).
  --credentials-dir DIR     Contains svn-username and svn-password
                            (default: $HOME/.config/episim-runner).
  --manifest FILE           Generated manifest (default: ./runs.json).
  --district NAME           District retained by the packer (default: Köln).
  --iterations NUMBER       Simulated days per variant (default: 10).
  --parallel NUMBER         Maximum simultaneous calculation containers
                            (default: 1).
  --run-memory VALUE        Memory limit per calculation (default: 8g).
  --run-cpus VALUE          CPU limit per calculation (default: 1).
  --run-id-template VALUE   Default: seed_{seed}-gamma_{gamma}-masks_{masks}.
                            Tokens: {seed}, {gamma}, {masks}.
                            In {gamma}, a decimal point is encoded as 'p'.
                            For one legacy masks value, a template may omit
                            {masks}; duplicate generated IDs are rejected.
  --keep-seeds              Do not average variants which differ only by seed.
  --skip-calculations       Only generate the manifest and run visualization.
  --dry-run                 Generate and print runs.json without any containers.
  -h, --help                Show this help.

Re-running the script is safe: calculation containers accept an identical
already-published SVN result and continue to the visualization stage.
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

require_value() {
  [[ $# -ge 2 && -n "$2" ]] || die "missing value for $1"
}

seeds_csv=""
gammas_csv=""
masks_csv=""
source_svn_url=""
target_svn_url=""
visualization_id=""
image="episim-runner:poc"
docker_command="${EPISIM_DOCKER_COMMAND:-docker}"
credentials_dir="${HOME}/.config/episim-runner"
manifest="${PWD}/runs.json"
district="Köln"
run_id_template='seed_{seed}-gamma_{gamma}-masks_{masks}'
keep_seeds=false
skip_calculations=false
dry_run=false
iterations=10
parallel=1
run_memory="8g"
run_cpus="1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --seeds) require_value "$@"; seeds_csv="$2"; shift 2 ;;
    --seeds=*) seeds_csv="${1#*=}"; shift ;;
    --gammas) require_value "$@"; gammas_csv="$2"; shift 2 ;;
    --gammas=*) gammas_csv="${1#*=}"; shift ;;
    --masks) require_value "$@"; masks_csv="$2"; shift 2 ;;
    --masks=*) masks_csv="${1#*=}"; shift ;;
    --source-svn-url) require_value "$@"; source_svn_url="$2"; shift 2 ;;
    --source-svn-url=*) source_svn_url="${1#*=}"; shift ;;
    --target-svn-url) require_value "$@"; target_svn_url="$2"; shift 2 ;;
    --target-svn-url=*) target_svn_url="${1#*=}"; shift ;;
    --visualization-id) require_value "$@"; visualization_id="$2"; shift 2 ;;
    --visualization-id=*) visualization_id="${1#*=}"; shift ;;
    --image) require_value "$@"; image="$2"; shift 2 ;;
    --image=*) image="${1#*=}"; shift ;;
    --credentials-dir) require_value "$@"; credentials_dir="$2"; shift 2 ;;
    --credentials-dir=*) credentials_dir="${1#*=}"; shift ;;
    --manifest) require_value "$@"; manifest="$2"; shift 2 ;;
    --manifest=*) manifest="${1#*=}"; shift ;;
    --district) require_value "$@"; district="$2"; shift 2 ;;
    --district=*) district="${1#*=}"; shift ;;
    --iterations) require_value "$@"; iterations="$2"; shift 2 ;;
    --iterations=*) iterations="${1#*=}"; shift ;;
    --parallel) require_value "$@"; parallel="$2"; shift 2 ;;
    --parallel=*) parallel="${1#*=}"; shift ;;
    --run-memory) require_value "$@"; run_memory="$2"; shift 2 ;;
    --run-memory=*) run_memory="${1#*=}"; shift ;;
    --run-cpus) require_value "$@"; run_cpus="$2"; shift 2 ;;
    --run-cpus=*) run_cpus="${1#*=}"; shift ;;
    --run-id-template) require_value "$@"; run_id_template="$2"; shift 2 ;;
    --run-id-template=*) run_id_template="${1#*=}"; shift ;;
    --keep-seeds) keep_seeds=true; shift ;;
    --skip-calculations) skip_calculations=true; shift ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

[[ -n "$seeds_csv" ]] || die "--seeds is required"
[[ -n "$gammas_csv" ]] || die "--gammas is required"
[[ -n "$masks_csv" ]] || die "--masks is required"
[[ -n "$source_svn_url" ]] || die "--source-svn-url is required"
[[ -n "$target_svn_url" ]] || die "--target-svn-url is required"
[[ "$visualization_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid --visualization-id"
[[ "$iterations" =~ ^[1-9][0-9]*$ ]] || die "--iterations must be a positive integer"
[[ "$parallel" =~ ^[1-9][0-9]*$ ]] || die "--parallel must be a positive integer"
[[ -n "$run_memory" ]] || die "--run-memory must not be empty"
[[ -n "$run_cpus" ]] || die "--run-cpus must not be empty"
[[ "$run_id_template" == *'{seed}'* ]] || die "--run-id-template must contain {seed}"
[[ "$run_id_template" == *'{gamma}'* ]] || die "--run-id-template must contain {gamma}"

IFS=',' read -r -a seeds <<< "$seeds_csv"
IFS=',' read -r -a gammas <<< "$gammas_csv"
IFS=',' read -r -a masks_values <<< "$masks_csv"
[[ ${#seeds[@]} -gt 0 && ${#gammas[@]} -gt 0 && ${#masks_values[@]} -gt 0 ]] || die "matrix lists must not be empty"

manifest_parent="$(dirname "$manifest")"
mkdir -p "$manifest_parent"
manifest_parent="$(cd "$manifest_parent" && pwd)"
manifest="${manifest_parent}/$(basename "$manifest")"
temporary_manifest="$(mktemp "${manifest_parent}/.runs.json.XXXXXX")"
trap 'rm -f "$temporary_manifest"' EXIT

first=true
seen='|'
run_ids=()
run_seeds=()
run_gammas=()
run_masks=()
{
  printf '{\n  "runs": [\n'
  for seed in "${seeds[@]}"; do
    seed="${seed//[[:space:]]/}"
    [[ "$seed" =~ ^-?[0-9]+$ ]] || die "invalid seed: $seed"
    for gamma in "${gammas[@]}"; do
      gamma="${gamma//[[:space:]]/}"
      [[ "$gamma" =~ ^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$ ]] || die "invalid gamma: $gamma"
      for masks in "${masks_values[@]}"; do
        masks="${masks//[[:space:]]/}"
        [[ "$masks" == "yes" || "$masks" == "no" ]] || die "invalid masks value: $masks"

        gamma_id="${gamma//./p}"
        gamma_id="${gamma_id//+/plus}"
        run_id="${run_id_template//\{seed\}/$seed}"
        run_id="${run_id//\{gamma\}/$gamma_id}"
        run_id="${run_id//\{masks\}/$masks}"
        [[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "generated invalid run ID: $run_id"
        [[ "$seen" != *"|${run_id}|"* ]] || die "duplicate generated run ID: $run_id"
        seen="${seen}${run_id}|"
        run_ids+=("$run_id")
        run_seeds+=("$seed")
        run_gammas+=("$gamma")
        run_masks+=("$masks")

        if [[ "$first" == true ]]; then
          first=false
        else
          printf ',\n'
        fi
        printf '    "%s"' "$run_id"
      done
    done
  done
  printf '\n  ]\n}\n'
} > "$temporary_manifest"

mv "$temporary_manifest" "$manifest"
trap - EXIT

printf 'Generated manifest: %s\n' "$manifest"
cat "$manifest"

if [[ "$dry_run" == true ]]; then
  exit 0
fi

command -v "$docker_command" >/dev/null 2>&1 || die "docker is not installed: $docker_command"
[[ -f "$credentials_dir/svn-username" ]] || die "missing $credentials_dir/svn-username"
[[ -f "$credentials_dir/svn-password" ]] || die "missing $credentials_dir/svn-password"
credentials_dir="$(cd "$credentials_dir" && pwd)"

run_variant() {
  local index="$1"
  local ordinal=$((index + 1))
  printf 'Starting calculation %d/%d: %s\n' "$ordinal" "${#run_ids[@]}" "${run_ids[$index]}"
  "$docker_command" run --rm \
    --name "episim-${visualization_id}-${ordinal}" \
    --memory="$run_memory" \
    --cpus="$run_cpus" \
    -v "${credentials_dir}:/var/run/secrets/episim:ro" \
    "$image" \
    run \
    "--seed=${run_seeds[$index]}" \
    "--gamma=${run_gammas[$index]}" \
    "--masks=${run_masks[$index]}" \
    "--iterations=${iterations}" \
    "--run-id=${run_ids[$index]}" \
    "--svn-url=${source_svn_url}"
}

active_pids=()
active_names=()

wait_for_oldest() {
  local pid="${active_pids[0]}"
  local run_id="${active_names[0]}"
  local status=0
  if wait "$pid"; then
    printf 'Finished calculation: %s\n' "$run_id"
  else
    status=$?
    printf 'Calculation failed with exit %d: %s\n' "$status" "$run_id" >&2
  fi
  if [[ ${#active_pids[@]} -gt 1 ]]; then
    active_pids=("${active_pids[@]:1}")
    active_names=("${active_names[@]:1}")
  else
    active_pids=()
    active_names=()
  fi

  if [[ $status -ne 0 ]]; then
    while [[ ${#active_pids[@]} -gt 0 ]]; do
      pid="${active_pids[0]}"
      wait "$pid" || true
      if [[ ${#active_pids[@]} -gt 1 ]]; then
        active_pids=("${active_pids[@]:1}")
      else
        active_pids=()
      fi
    done
    exit "$status"
  fi
}

if [[ "$skip_calculations" == false ]]; then
  printf 'Running %d calculations with parallelism %d (%s RAM and %s CPU each).\n' \
    "${#run_ids[@]}" "$parallel" "$run_memory" "$run_cpus"
  for index in "${!run_ids[@]}"; do
    run_variant "$index" &
    active_pids+=("$!")
    active_names+=("${run_ids[$index]}")
    if [[ ${#active_pids[@]} -ge $parallel ]]; then
      wait_for_oldest
    fi
  done
  while [[ ${#active_pids[@]} -gt 0 ]]; do
    wait_for_oldest
  done
fi

printf 'All requested calculations are available; starting visualization.\n'

docker_arguments=(
  run --rm
  --name "episim-visualization-${visualization_id}"
  --memory="$run_memory"
  --cpus="$run_cpus"
  -v "${credentials_dir}:/var/run/secrets/episim:ro"
  -v "${manifest}:/etc/episim-batch/runs.json:ro"
  "$image"
  visualize
  --runs-file=/etc/episim-batch/runs.json
  "--source-svn-url=${source_svn_url}"
  "--target-svn-url=${target_svn_url}"
  "--visualization-id=${visualization_id}"
  "--district=${district}"
)

if [[ "$keep_seeds" == true ]]; then
  docker_arguments+=(--keep-seeds)
fi

"$docker_command" "${docker_arguments[@]}"
printf 'Published visualization: %s/%s\n' "${target_svn_url%/}" "$visualization_id"
