# syntax=docker/dockerfile:1

# ExcaliburDB node image.
#
# Two stages: Gradle builds the shadow jar against a JDK 21 toolchain, and the node runs on a
# Temurin 21 JRE as an unprivileged user with its data directory on a volume.
#
# The three JVM flags below the build are not tuning, they are the conditions the process
# starts under at all:
#
#   --enable-preview                 Every class in this project is compiled with
#                                    --enable-preview (root build.gradle.kts, JavaCompile), and
#                                    a preview class file is refused by a JVM started without
#                                    it: UnsupportedClassVersionError before main() runs.
#   --enable-native-access=ALL-UNNAMED
#                                    The storage engine maps segments through the Panama FFM
#                                    API (java.lang.foreign.Arena). Without it the first native
#                                    downcall fails at run time, which is later than startup
#                                    and harder to read.
#   --add-modules=jdk.incubator.vector
#                                    excalibur-vector's distance kernels are SIMD. The module
#                                    is not resolved by default, and VectorApiSupport then
#                                    falls back to scalar arithmetic silently - a node that
#                                    works and is several times slower at every search.
#
# The first two are what excalibur-api/build.gradle.kts puts in applicationDefaultJvmArgs, so
# this image launches the jar the way the generated start scripts launch it. The third is what
# excalibur-vector/build.gradle.kts adds for compilation and for its own JVM forks.
#
# Configuration: excalibur.properties is read from the working directory, then from the
# classpath (the packaged default in excalibur-api/src/main/resources). Any setting can be
# supplied from the environment instead - EXCALIBUR_<SECTION>_<NAME>, upper-cased with
# hyphens as underscores, overrides the file (com.eonmux.excalibur.config.EnvOverrides). No secret is
# baked into this image: pass excalibur.llm.api-key as EXCALIBUR_LLM_API_KEY at run time.
#
# Reachability: the API binds 127.0.0.1 by default, and binding anything else requires a bearer
# token and TLS (excalibur.api.bind-address, ApiSecurityConfig). To serve traffic from outside
# the container, set EXCALIBUR_API_BIND_ADDRESS=0.0.0.0, EXCALIBUR_API_AUTH_TOKEN=<token>, and
# either the TLS keystore settings or EXCALIBUR_API_ALLOW_PLAINTEXT=true where something in
# front of this process terminates TLS. The image sets none of them: a default that listened on
# every interface unauthenticated would be the one mistake this policy exists to prevent.

# ==================== Build ====================

FROM eclipse-temurin:21-jdk-jammy AS build

# Gradle's caches live here, so the mount below is what keeps a rebuild from re-resolving
# every dependency.
ENV GRADLE_USER_HOME=/gradle-home

WORKDIR /workspace

# The wrapper and the build scripts first, so the layer that downloads the Gradle distribution
# is not invalidated by a source edit.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/gradle-home,sharing=locked \
    ./gradlew --no-daemon --version > /dev/null

COPY . .

# Tests run in CI against the whole tree; this stage builds the artefact. The jar is copied out
# of the cached build directory so the runtime stage can take it from a stable path.
RUN --mount=type=cache,target=/gradle-home,sharing=locked \
    ./gradlew --no-daemon :excalibur-api:shadowJar :excalibur-fsck:installDist -x test \
 && cp excalibur-api/build/libs/excalibur.jar /workspace/excalibur.jar

# ==================== Runtime ====================

FROM eclipse-temurin:21-jre-jammy AS runtime

LABEL org.opencontainers.image.title="ExcaliburDB" \
      org.opencontainers.image.description="Disk-backed data relevancy retrieval engine for LLMs" \
      org.opencontainers.image.source="https://github.com/aeslampanah/excaliburdb" \
      org.opencontainers.image.licenses="Apache-2.0"

# curl is the HEALTHCHECK's only dependency. The Temurin JRE already carries every module the
# node needs - jdk.httpserver for the API, java.net.http for the LLM client, java.management
# for the JMX metrics, jdk.incubator.vector for the SIMD kernels - so nothing else is added.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# A fixed uid, so a bind-mounted host directory can be given the same owner up front.
ARG EXCALIBUR_UID=10001
ARG EXCALIBUR_GID=10001

RUN groupadd --system --gid "${EXCALIBUR_GID}" excalibur \
 && useradd --system --uid "${EXCALIBUR_UID}" --gid excalibur \
      --home-dir /var/lib/excalibur --shell /usr/sbin/nologin excalibur \
 && mkdir -p /var/lib/excalibur /opt/excalibur \
 && chown excalibur:excalibur /var/lib/excalibur

# Owned by root and read-only to the process: the node has no reason to be able to rewrite its
# own code, and an ingestion path that could would be a remote code execution.
COPY --from=build --chown=root:root --chmod=444 \
     /workspace/excalibur.jar /opt/excalibur/excalibur.jar

# The integrity checker, so it is reachable when the node will not start - which is the only
# time anyone wants it. `docker run --entrypoint excalibur-fsck <image> /var/lib/excalibur`
# against the same volume: it maps every file READ_ONLY, and repair is off unless --repair is
# passed, so a check cannot make a damaged directory worse.
COPY --from=build --chown=root:root \
     /workspace/excalibur-fsck/build/install/excalibur-fsck /opt/excalibur-fsck
ENV PATH="/opt/excalibur-fsck/bin:${PATH}"

# Where segments, write-ahead logs, catalogs, the lexical index and the source registry live.
# ExcaliburApplication reads this variable and nothing else; there is no properties key for it.
ENV EXCALIBUR_DATA_DIR=/var/lib/excalibur

# Heap from the container's limit rather than a fixed -Xmx, so the same image is correct under
# every `docker run -m`. 60% and not the usual 75%: this engine keeps its segments in
# memory-mapped Arenas outside the heap, and a heap sized to crowd out the page cache turns
# every search into disk reads. ExitOnOutOfMemoryError rather than a JVM that survives an OOM
# in a worker and keeps answering /health while indexing nothing - the same three flags
# excalibur-api/build.gradle.kts gives the start scripts, with the dump written to the volume
# because the working directory is not the process's to write.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/lib/excalibur \
-XX:+ExitOnOutOfMemoryError"

USER excalibur
WORKDIR /var/lib/excalibur
VOLUME ["/var/lib/excalibur"]

EXPOSE 8080

# /health/ready, not /health. /health is liveness and answers 200 while the node is still
# opening segments or has been drained; readiness answers 200 or 503 on whether this node can
# serve a query, and it is the one route that is unauthenticated by policy rather than by
# accident, so the probe needs no token. Its port follows EXCALIBUR_PORT, which is what
# ListenPort reads first. The start period covers segment recovery on a large data directory.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1:${EXCALIBUR_PORT:-8080}/health/ready" || exit 1

# exec, so the JVM is pid 1 and SIGTERM reaches its shutdown hook; the hook closes the storage
# engine and the chunk index, which is what publishes the tail of an ingestion run. Allow at
# least ExcaliburApplication.SHUTDOWN_TIMEOUT_SECONDS (30) before SIGKILL: `docker run
# --stop-timeout 40`, or terminationGracePeriodSeconds: 40.
# "$@" carries through, so `docker run <image> --config /etc/excalibur/excalibur.properties`
# reaches the application's own argument parsing.
ENTRYPOINT ["/bin/sh", "-c", \
  "exec java --enable-preview --enable-native-access=ALL-UNNAMED \
--add-modules=jdk.incubator.vector ${JAVA_OPTS} -jar /opt/excalibur/excalibur.jar \"$@\"", "--"]
