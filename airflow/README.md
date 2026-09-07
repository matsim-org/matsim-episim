# Local Airflow proof of concept

This setup runs Airflow 3.3.1 with CeleryExecutor on one Docker host. Airflow itself runs in Docker Compose;
`DockerOperator` uses the host Docker Engine to start one `episim-runner` container per matrix item. Once every
variant succeeds, a final container downloads the listed runs from SVN and publishes the visualization.

## Prerequisites

- Docker Engine with Compose v2.14 or newer.
- At least 4 GB for Airflow plus 8 GB per simultaneous Episim calculation.
- A locally available `episim-runner:poc` image.
- `${HOME}/.config/episim-runner/svn-username` and `svn-password`, readable only by the user running Docker.

## First start

On macOS with Homebrew/Colima, install the missing Compose plugin if `docker compose version` fails:

```bash
brew install docker-compose
```

Homebrew may ask you to add its Docker CLI plugin directory to `~/.docker/config.json`; follow the caveat printed
by `brew info docker-compose`, then verify with `docker compose version`. Until the plugin directory is registered,
the equivalent standalone command `docker-compose` can be used for every command below.

Build the runner from the project root:

```bash
./mvnw package
docker build -f Dockerfile.runner -t episim-runner:poc .
```

Configure Airflow:

```bash
cd airflow
cp .env.example .env
```

Edit `.env` and set these values:

- `EPISIM_CREDENTIALS_DIR`: absolute host path containing `svn-username` and `svn-password`.
- `EPISIM_SOURCE_SVN_URL`: existing SVN directory where immutable variant directories are created.
- `EPISIM_TARGET_SVN_URL`: existing SVN directory where viewer packages are created.
- `AIRFLOW_UID`: output of `id -u`; this is required when the Docker socket is readable only by your user.
- `DOCKER_SOCKET`: leave `/var/run/docker.sock` for Docker Desktop and Colima.
- `DOCKER_GID`: numeric group owning the daemon socket. On Colima, use
  `colima ssh -- stat -c '%g' /var/run/docker.sock`; on Linux, use `stat -c '%g' /var/run/docker.sock`.
- `EPISIM_MAX_PARALLEL`: maximum number of simultaneous Airflow tasks for this DAG.

Initialize and start Airflow:

```bash
docker compose up airflow-init
docker compose up -d
```

Open <http://localhost:8080>, sign in with `airflow` / `airflow`, enable `episim_local_matrix`, and press
**Trigger DAG**. The form allows changing seeds, gammas, masks, iterations, SVN locations, and visualization ID.
Use a new `visualization_id` for a different matrix. An identical rerun is safe because runner publications are
immutable and idempotent.

Follow services and task logs with:

```bash
docker compose ps
docker compose logs -f airflow-worker
```

Stop the system without deleting its database:

```bash
docker compose down
```

This Compose setup intentionally gives the Airflow worker access to the host Docker socket. That access is
equivalent to administrative access to this Docker host, so use it only on a dedicated development or compute
machine and do not allow untrusted users to edit DAG files.
