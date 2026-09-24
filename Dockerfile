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
# All three are in excalibur-api/build.gradle.kts's applicationDefaultJvmArgs, so this image
# launches the jar the way the generated start scripts launch it.
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
#
# Four directories belong to the node's user, and only the first is declared a volume:
#
#   /var/lib/excalibur              The data directory. A VOLUME, so it outlives the container.
#   /var/lib/excalibur-diagnostics  Where an out-of-memory heap dump is written. Mount it to keep
#                                   dumps past `docker rm`; unmounted, they sit in the
#                                   container's writable layer until the container is removed.
#   /var/lib/excalibur-quarantine   Where `excalibur-fsck --repair --quarantine-dir` moves the
#                                   files it sets aside. Mount it, or the evidence a repair
#                                   preserved is deleted with a `docker run --rm` container.
#   /var/lib/excalibur-backups      Where `excalibur-backup snapshot --out` writes an image when
#                                   run from this image. Mount it, for the same reason.
#
# A named volume mounted on any of them takes that directory's owner, so the process can write
# to it; a host directory bind-mounted there has to be given uid 10001 first. The last three are
# not VOLUMEs on purpose. A declared volume that is not mounted becomes an anonymous volume,
# which `--rm` deletes with the container - so a declaration would promise a persistence that
# only an explicit mount delivers, and hide that it was missing.

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
    ./gradlew --no-daemon :excalibur-api:shadowJar :excalibur-fsck:installDist \
      :excalibur-backup:installDist -x test \
 && cp excalibur-api/build/libs/excalibur.jar /workspace/excalibur.jar

# ==================== Runtime ====================

FROM eclipse-temurin:21-jre-jammy AS runtime

LABEL org.opencontainers.image.title="ExcaliburDB" \
      org.opencontainers.image.description="Disk-backed data relevancy retrieval engine for LLMs" \
      org.opencontainers.image.source="https://github.com/EonMux/ExcaliburDB" \
      org.opencontainers.image.licenses="Apache-2.0"

# curl is the HEALTHCHECK's only dependency (docker/excalibur-healthcheck). The Temurin JRE already carries every module the
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
 && mkdir -p /var/lib/excalibur /var/lib/excalibur-diagnostics /var/lib/excalibur-quarantine \
      /var/lib/excalibur-backups /opt/excalibur \
 && chown excalibur:excalibur /var/lib/excalibur /var/lib/excalibur-diagnostics \
      /var/lib/excalibur-quarantine /var/lib/excalibur-backups

# Owned by root and read-only to the process: the node has no reason to be able to rewrite its
# own code, and an ingestion path that could would be a remote code execution.
COPY --from=build --chown=root:root --chmod=444 \
     /workspace/excalibur.jar /opt/excalibur/excalibur.jar

# The integrity checker, so it is reachable when the node will not start - which is the only
# time anyone wants it. `docker run --entrypoint excalibur-fsck <image> /var/lib/excalibur`
# against the same volume: without --repair it maps every file READ_ONLY and changes nothing.
# --repair does write: it moves files into the quarantine directory, marks a catalog row purged
# in place when the segment it names is gone, and clears the opcode of a write-ahead log's torn
# last record so the log reads as ending before it. Its default quarantine is a new directory
# beside the data directory, in /var/lib, which this user cannot create, so a repair in this
# image passes --quarantine-dir=/var/lib/excalibur-quarantine with that directory mounted.
COPY --from=build --chown=root:root \
     /workspace/excalibur-fsck/build/install/excalibur-fsck /opt/excalibur-fsck

# The backup tool, for the same reason: a snapshot is taken with the node stopped, and this
# image is where the JVM flags it needs are already right. `docker run --entrypoint
# excalibur-backup <image> snapshot --data-dir /var/lib/excalibur --out
# /var/lib/excalibur-backups/<name>`, with the node's volume and a backup volume mounted.
COPY --from=build --chown=root:root \
     /workspace/excalibur-backup/build/install/excalibur-backup /opt/excalibur-backup
ENV PATH="/opt/excalibur-fsck/bin:/opt/excalibur-backup/bin:${PATH}"

# Where segments, write-ahead logs, catalogs, the lexical index and the source registry live.
# ExcaliburApplication reads this variable and nothing else; there is no properties key for it.
ENV EXCALIBUR_DATA_DIR=/var/lib/excalibur

# Heap from the container's limit rather than a fixed -Xmx, so the same image is correct under
# every `docker run -m`. 60% and not the usual 75%: this engine keeps its segments in
# memory-mapped Arenas outside the heap, and a heap sized to crowd out the page cache turns
# every search into disk reads. ExitOnOutOfMemoryError rather than a JVM that survives an OOM
# in a worker and keeps answering /health while indexing nothing - the same three flags
# excalibur-api/build.gradle.kts gives the start scripts. The dump goes to a directory of its
# own and not the data directory: a dump the size of the heap beside the segments is state
# excalibur-backup would otherwise have to leave out and excalibur-fsck would have to explain,
# and on a volume sized for the data it can be what fills the disk.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:+UseG1GC \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/lib/excalibur-diagnostics \
-XX:+ExitOnOutOfMemoryError"

USER excalibur
WORKDIR /var/lib/excalibur
VOLUME ["/var/lib/excalibur"]

EXPOSE 8080

# Liveness, from the address the node actually bound. The node writes its liveness URL into
# its data directory's lock file (/var/lib/excalibur/.excalibur-lock, `health-url=`) once its
# listener is up - https when TLS is on, the port it took however it was configured, the
# interface it bound, loopback for a wildcard - and the script reads it from there, so no
# setting has to be repeated here and none can disagree. /health rather than /health/ready: a
# health check drives restarts, and a drained or still-recovering node is alive; readiness is
# for whatever routes traffic. /health is unauthenticated by policy, so the probe needs no token.
# The start period covers segment recovery on a large data directory. See the script for TLS
# client certificates.
COPY --chown=root:root --chmod=755 docker/excalibur-healthcheck \
     /opt/excalibur/bin/excalibur-healthcheck
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD ["/opt/excalibur/bin/excalibur-healthcheck"]

# exec, so the JVM is pid 1 and SIGTERM reaches its shutdown hook; the hook closes the storage
# engine and the chunk index, which is what publishes the tail of an ingestion run. The hook
# finishes within excalibur.api.shutdown-timeout-seconds (30 by default); allow that plus 10
# before SIGKILL: `docker run --stop-timeout 40`, or terminationGracePeriodSeconds: 40.
# "$@" carries through, so `docker run <image> --config /etc/excalibur/excalibur.properties`
# reaches the application's own argument parsing.
ENTRYPOINT ["/bin/sh", "-c", \
  "exec java --enable-preview --enable-native-access=ALL-UNNAMED \
--add-modules=jdk.incubator.vector ${JAVA_OPTS} -jar /opt/excalibur/excalibur.jar \"$@\"", "--"]
