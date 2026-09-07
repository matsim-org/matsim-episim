from __future__ import annotations

import json
import os
import re
from datetime import datetime, timedelta, timezone
from itertools import product
from pathlib import Path

from airflow.providers.docker.operators.docker import DockerOperator
from airflow.sdk import DAG, Param, get_current_context, task
from docker.types import Mount


RUNNER_IMAGE = os.environ.get("EPISIM_RUNNER_IMAGE", "episim-runner:poc")
CREDENTIALS_DIR = os.environ.get("EPISIM_CREDENTIALS_DIR", "/missing/episim-credentials")
SOURCE_SVN_URL = os.environ.get("EPISIM_SOURCE_SVN_URL", "https://svn.example.org/results")
TARGET_SVN_URL = os.environ.get("EPISIM_TARGET_SVN_URL", "https://svn.example.org/visualizations")
MAX_PARALLEL = int(os.environ.get("EPISIM_MAX_PARALLEL", "1"))
RUN_MEMORY = os.environ.get("EPISIM_RUN_MEMORY", "8g")
RUN_CPUS = float(os.environ.get("EPISIM_RUN_CPUS", "1"))
WORK_VOLUME = "episim-airflow-work"
WORK_ROOT = Path("/opt/airflow/episim-work")


def gamma_for_id(value: int | float) -> str:
    return str(value).replace(".", "p").replace("+", "plus")


def run_id(seed: int, gamma: int | float, masks: str) -> str:
    return f"seed_{seed}-gamma_{gamma_for_id(gamma)}-masks_{masks}"


credentials_mount = Mount(
    source=CREDENTIALS_DIR,
    target="/var/run/secrets/episim",
    type="bind",
    read_only=True,
)
work_mount = Mount(
    source=WORK_VOLUME,
    target=str(WORK_ROOT),
    type="volume",
)


with DAG(
    dag_id="episim_local_matrix",
    description="Run a seed × gamma × masks matrix and publish its visualization.",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    max_active_tasks=MAX_PARALLEL,
    tags=["episim", "docker", "poc"],
    params={
        "seeds": Param([4711, 4712], type="array", items={"type": "integer"}),
        "gammas": Param([0.8, 1.0], type="array", items={"type": "number", "exclusiveMinimum": 0}),
        "masks": Param(["yes"], type="array", items={"type": "string", "enum": ["yes", "no"]}),
        "iterations": Param(10, type="integer", minimum=1),
        "source_svn_url": Param(SOURCE_SVN_URL, type="string", minLength=1),
        "target_svn_url": Param(TARGET_SVN_URL, type="string", minLength=1),
        "visualization_id": Param("airflow-poc", type="string", pattern="^[A-Za-z0-9._-]+$"),
        "district": Param("Köln", type="string", minLength=1),
        "keep_seeds": Param(False, type="boolean"),
    },
) as dag:

    @task
    def prepare_variant_commands() -> list[list[str]]:
        context = get_current_context()
        params = context["params"]
        seeds = [int(value) for value in params["seeds"]]
        gammas = list(params["gammas"])
        masks_values = list(params["masks"])
        iterations = int(params["iterations"])

        combinations = list(product(seeds, gammas, masks_values))
        if not combinations:
            raise ValueError("The seed × gamma × masks matrix must not be empty")

        ids = [run_id(seed, gamma, masks) for seed, gamma, masks in combinations]
        if len(ids) != len(set(ids)):
            raise ValueError("The matrix generates duplicate run IDs")

        source_url = str(params["source_svn_url"])
        variant_commands = []
        for (seed, gamma, masks), variant_id in zip(combinations, ids, strict=True):
            variant_commands.append([
                "run",
                f"--seed={seed}",
                f"--gamma={gamma}",
                f"--masks={masks}",
                f"--iterations={iterations}",
                f"--run-id={variant_id}",
                f"--svn-url={source_url}",
            ])

        return variant_commands

    @task
    def prepare_visualization_command(variant_commands: list[list[str]]) -> list[str]:
        context = get_current_context()
        params = context["params"]
        ids = []
        for command in variant_commands:
            run_id_argument = next(argument for argument in command if argument.startswith("--run-id="))
            ids.append(run_id_argument.removeprefix("--run-id="))

        airflow_run_id = re.sub(r"[^A-Za-z0-9._-]+", "_", context["run_id"])
        manifest_dir = WORK_ROOT / airflow_run_id
        manifest_dir.mkdir(parents=True, exist_ok=True)
        manifest_path = manifest_dir / "runs.json"
        manifest_path.write_text(json.dumps({"runs": ids}, indent=2) + "\n", encoding="utf-8")

        visualization_command = [
            "visualize",
            f"--runs-file={manifest_path}",
            f"--source-svn-url={params['source_svn_url']}",
            f"--target-svn-url={params['target_svn_url']}",
            f"--visualization-id={params['visualization_id']}",
            f"--district={params['district']}",
        ]
        if params["keep_seeds"]:
            visualization_command.append("--keep-seeds")

        return visualization_command

    variant_commands = prepare_variant_commands()
    visualization_command = prepare_visualization_command(variant_commands)

    variants = DockerOperator.partial(
        task_id="run_variant",
        image=RUNNER_IMAGE,
        docker_url="unix://var/run/docker.sock",
        mounts=[credentials_mount],
        mount_tmp_dir=False,
        auto_remove="success",
        mem_limit=RUN_MEMORY,
        cpus=RUN_CPUS,
        environment={"JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
        retries=1,
        retry_delay=timedelta(minutes=1),
    ).expand(command=variant_commands)

    visualization = DockerOperator(
        task_id="publish_visualization",
        image=RUNNER_IMAGE,
        command=visualization_command,
        docker_url="unix://var/run/docker.sock",
        mounts=[credentials_mount, work_mount],
        mount_tmp_dir=False,
        auto_remove="success",
        mem_limit=RUN_MEMORY,
        cpus=RUN_CPUS,
        environment={"JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
        retries=1,
        retry_delay=timedelta(minutes=1),
    )

    visualization_command >> variants >> visualization
