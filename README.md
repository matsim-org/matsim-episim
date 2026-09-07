# MATSim Episim Example

![Build Status](https://github.com/matsim-org/matsim-episim-libs/workflows/build/badge.svg?branch=master)
![license](https://img.shields.io/github/license/matsim-org/matsim-episim-libs.svg)
![JDK](https://img.shields.io/badge/JDK-11+-green.svg)


This repository contains an example project for an epidemic simulation based on MATSim, provided by the [Transport Systems Planning and Transport Telematics group](https://www.vsp.tu-berlin.de) of [Technische Universität Berlin](https://www.tu-berlin.de).

The internal code and core models can be found at https://github.com/matsim-org/matsim-episim-libs.

<a rel="TU Berlin" href="https://www.vsp.tu-berlin.de"><img src="https://svn.vsp.tu-berlin.de/repos/public-svn/ueber_uns/logo/TUB_Logo.png" width="15%" height="15%"/></a>

### How to use Episim

The [Maven](https://maven.apache.org/what-is-maven.html) build system is required to build the example project.
This repository comes with a Maven wrapper script that can be used if Maven is not already installed on the pc.


In order to perform an epidemic simulation you first need a MATSim events file.
To get started you can also use a provided event file from the [OpenBerlin Scenario](https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/berlin/berlin-v5.4-1pct-schools/output-berlin-v5.4-1pct-schools/berlin-v5.4-1pct-schools.output_events_for_episim.xml.gz).

It is best to edit the scenarios with your IDE from the `org.matsim.run` package.
In `org.matsim.run.modules` you can find example scenarios that you may adapt or use, to create your own configuration.
To run them you can create a standalone jar file with:

    ./mvnw package

and run it with (where `OpenBerlinScenario` is the name of the scenario you want to run):

    java -jar matsim-episim-example-1.0-SNAPSHOT.jar --modules org.matsim.episim.run.modules.OpenBerlinScenario
    

### Generating mobility traces events file

If you have a MATSim scenario you can also create your own event file to be used for an epidemic simulation.
All that is needed is the population and the event file of one iteration.

Run `DownSampleScenario` to extract the necessary information: 

    java -jar matsim-episim-example-1.0-SNAPSHOT.jar scenarioCreation downSample 0.1 \
        --population <Path to plans.xml> --events <Path to events.xml>

This example will write a 10% sample into `output` that can be used in the subsequent steps.


### Batch Runs

The `BatchRun` class allows running one scenario many times with a different parametrization (possibly in parallel).

    java -jar matsim-episim-example-1.0-SNAPSHOT.jar runParallel \
        --setup org.matsim.episim.run.batch.OpenBerlinBatch \
        --params org.matsim.episim.run.batch.OpenBerlinBatch$Params

The `OpenBerlinBatch` class shows how such a batch run can be defined. It is also possible prepare executable
scripts for running the batch on a cluster:

    java -jar matsim-episim-example-1.0-SNAPSHOT.jar createBattery \
        --setup org.matsim.episim.run.batch.OpenBerlinBatch \
        --params org.matsim.episim.run.batch.OpenBerlinBatch$Params

### Single variant runner

`EpisimVariantRunner` executes exactly one Cologne variant and publishes the result as one SVN revision. The
`gamma` argument is applied as a multiplier to the scenario calibration parameter. Credentials are read from files;
the password is passed to the SVN client over standard input and is neither stored in the command line nor cached.

    java -cp matsim-episim-*.jar org.matsim.episim.run.EpisimVariantRunner \
        --seed 4711 \
        --gamma 0.8 \
        --iterations 10 \
        --svn-url https://svn.example.org/repos/episim/results/batch-001 \
        --svn-username-file /secure/svn-username \
        --svn-password-file /secure/svn-password

Each run is published below the supplied URL using a directory such as `seed_4711-gamma_0p8-masks_yes`. Dots are
not used in run IDs because MATSim post-processing treats the first dot as the end of the output prefix. The directory contains
the stable request, execution metadata, resolved MATSim config, zipped simulation output, and a `_SUCCESS` marker.
Re-running the same request is safe: an identical published request is accepted, while a conflicting request fails.

To build the runner image, first package the application and then build the runtime-only image:

    mvn package
    docker build -f Dockerfile.runner -t episim-runner:poc .

The same image exposes two operations. Run one simulation variant with `run` (the credentials directory contains
files named `svn-username` and `svn-password`):

    docker run --rm \
        --mount type=bind,source=$HOME/.config/episim-runner,target=/var/run/secrets/episim,readonly \
        episim-runner:poc run \
        --seed 4711 \
        --gamma 0.8 \
        --iterations 10 \
        --svn-url https://svn.example.org/repos/episim/results/batch-001

After all variants complete, create a JSON manifest containing exactly the immutable SVN run directories to collect:

    {
      "runs": [
        "seed_4711-gamma_0p8-masks_yes",
        "seed_4712-gamma_0p8-masks_yes"
      ]
    }

Then collect, pack, and publish the visualization with `visualize`:

    docker run --rm \
        --mount type=bind,source=$HOME/.config/episim-runner,target=/var/run/secrets/episim,readonly \
        --mount type=bind,source=/absolute/path/to/runs.json,target=/etc/episim-batch/runs.json,readonly \
        episim-runner:poc visualize \
        --runs-file /etc/episim-batch/runs.json \
        --source-svn-url https://svn.example.org/repos/episim/results/batch-001 \
        --target-svn-url https://svn.example.org/repos/episim/visualizations \
        --visualization-id batch-001 \
        --district Köln

Only runs from the manifest are downloaded. Every run must contain `_SUCCESS`, and the final viewer directory is
published in a single SVN revision. By default, seeds with equal gamma, iteration, and mask parameters are averaged;
add `--keep-seeds` to retain each seed as a separate viewer run.

The Kubernetes Secret must contain keys named `svn-username` and `svn-password`. Kubernetes examples are available
in `deploy/variant-job.yaml` and `deploy/visualization-job.yaml`.

For a local matrix, `scripts/publish-visualization-matrix.sh` starts one calculation container for every item in the
Cartesian product of seeds, gammas, and masks. After all calculations are successfully published to SVN, it creates
`runs.json`, starts the visualization container, and publishes the final viewer package. Use `--parallel` to control
how many heavy calculations run simultaneously:

    ./scripts/publish-visualization-matrix.sh \
        --seeds 4711,4712 \
        --gammas 0.8,1.0 \
        --masks yes,no \
        --iterations 10 \
        --parallel 2 \
        --run-memory 8g \
        --run-cpus 1 \
        --source-svn-url https://svn.example.org/repos/episim/results/batch-001 \
        --target-svn-url https://svn.example.org/repos/episim/visualizations \
        --visualization-id batch-001

The same flow can be orchestrated and monitored with a local Apache Airflow instance. See
[`airflow/README.md`](airflow/README.md) for the Docker Compose setup and the parameterized `episim_local_matrix` DAG.


### Licenses

The **MATSim program code** in this repository is distributed under the terms of the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.html). The MATSim program code are files that reside in the `src` directory hierarchy and typically end with `*.java`.

The **MATSim input files, output files, analysis data and visualizations** are licensed under a <a rel="license" href="https://creativecommons.org/licenses/by/4.0/">Creative Commons Attribution 4.0 International License</a>.
<a rel="license" href="https://creativecommons.org/licenses/by/4.0/"><img alt="Creative Commons License" style="border-width:0" src="https://i.creativecommons.org/l/by/4.0/80x15.png" /></a><br /> MATSim input files are those that are used as input to run MATSim. They often, but not always, have a header pointing to matsim.org. They typically reside in the `scenarios` directory hierarchy. MATSim output files, analysis data, and visualizations are files generated by MATSim runs, or by postprocessing.  They typically reside in a directory hierarchy starting with `output`.

**Other data files**, in particular in `original-input-data`, have their own individual licenses that need to be individually clarified with the copyright holders.


### More information

For more information about the methodology and preliminary results, see our website at https://covid-sim.info/ .

For more information about MATSim, see here: https://www.matsim.org/.
