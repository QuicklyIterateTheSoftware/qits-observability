# qits-observability as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# service/target/qits-observability in about 80 seconds with no container involved. What this
# Dockerfile adds is the image around it — and, incidentally, a way for a machine with no GraalVM (a
# CI runner, a laptop on a plain OpenJDK) to still get a binary, since the builder stage brings its
# own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/observability:latest -f docker/Dockerfile .
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a module list. Every module here is an unpublished 1.0.0-SNAPSHOT (no
# distributionManagement, no <repositories>), so a builder container with an empty ~/.m2 has nowhere
# to get one but the sources beside it. Copying module by module would mean editing this file every
# time one is added, and forgetting to would fail the build with an unresolved dependency rather than
# anything that names the real cause. `.dockerignore` is what keeps this cheap — without it this line
# drags in the ~119 MB of target/ output sitting next to it, and a stale target/ can shadow what this
# stage compiles.
COPY . .

# `-pl service -am` builds the app and its dependency closure, not the whole reactor. That is not a
# module list — it names the one module this image ships, which the COPY below already names anyway,
# so leaving it out gains nothing. What it buys: this repo will publish libraries as well as an
# image, and a reactor then holds modules that exist for consumers rather than for the binary. On a
# whole-reactor build a compile error in one of those would block deploying this service, and build
# time would grow with the published surface instead of with the app. The image build should depend
# on exactly what the image contains.
#
# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests because the suite is not this stage's gate — `./mvnw verify` on a clone is, and it is
# the declared one (AGENTS.md: a clone of this repo alone builds and tests green). Note the `native`
# profile also flips skipITs to false, but `package` stops short of the integration-test phase, so
# OtelReceiverIT does not run here either; `./mvnw verify -Dnative` is what exercises the binary
# against real OTLP bodies.
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-observability` — the deployable's name, not
# the module's (the module is `qits-telemetry`, in service/) — so the process is identifiable in
# `ps` / `docker top` as the thing the gateway routes to.
COPY --from=build --chown=1001:root /src/service/target/qits-observability /work/qits-observability
RUN chmod +x /work/qits-observability

# One port, and it is the only one: the OTLP receiver is in-process JAX-RS on the ordinary HTTP port
# (`POST /observability/api/otel/v1/{traces,logs,metrics}`), not a collector's 4317/4318 listener.
# Nothing here enables a separate management interface either, so 8080 carries ingest, the REST query
# surface, the `observability` MCP server and /observability/q alike. Exporters are pointed at a
# literal URL under that same port — see application.properties on `quarkus.rest.path`.
EXPOSE 8080
USER 1001

# -Dquarkus.http.host=0.0.0.0 because Quarkus binds 127.0.0.1 by default, which inside a container
# means reachable by nothing. Unlike the workspace-daemon, that is the wanted posture here: this
# service is addressed by its peers on qits-net.
ENTRYPOINT ["/work/qits-observability", "-Dquarkus.http.host=0.0.0.0"]
