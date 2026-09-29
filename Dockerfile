# matsim-episim runtime image: the built application, a Java runtime and svn. No run, credentials or output inside.
#   podman build -t matsim-episim --build-arg GIT_COMMIT=$(git rev-parse HEAD) .
#   podman run --rm matsim-episim version

# the jar does not depend on the platform, so this stage always runs natively, also for a multi-arch image
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
# dependencies first, so source changes do not download them again
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline || true
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package \
	&& mkdir /out && mv /src/matsim-episim-*.jar /out/episim.jar

FROM eclipse-temurin:25-jre-noble
ARG GIT_COMMIT=unknown
ARG GIT_DIRTY=false
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="matsim-episim" \
      org.opencontainers.image.source="https://github.com/matsim-org/matsim-episim" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.revision="${GIT_COMMIT}"

RUN apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates subversion \
	&& rm -rf /var/lib/apt/lists/* \
	&& groupadd --gid 10001 episim \
	&& useradd --uid 10001 --gid 10001 --create-home episim \
	&& mkdir -p /opt/episim /output /scenarios \
	&& chown 10001:10001 /opt/episim /output /scenarios

WORKDIR /opt/episim
COPY --from=build --chown=10001:10001 /out/episim.jar episim.jar
# default scenarios; --scenario /scenarios/<Name> (a mounted folder) uses external ones instead
COPY --chown=10001:10001 Scenarios Scenarios
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/entrypoint
RUN printf 'gitCommit=%s\ngitDirty=%s\nbuildDate=%s\n' "$GIT_COMMIT" "$GIT_DIRTY" "$BUILD_DATE" > BUILD_INFO

ENV EPISIM_OUTPUT=/output \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"
USER 10001:10001
VOLUME ["/output"]
ENTRYPOINT ["entrypoint"]
CMD ["help"]
