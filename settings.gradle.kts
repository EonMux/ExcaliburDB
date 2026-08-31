// Root settings file
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "excaliburdb"

include(
    "excalibur-config",
    "excalibur-metrics",
    "excalibur-core",
    "excalibur-test",
    "excalibur-store",
    "excalibur-filter",
    "excalibur-vector",
    "excalibur-graph",
    "excalibur-retrieval",
    "excalibur-tenancy",
    "excalibur-planner",
    "excalibur-shard",
    "excalibur-query",
    "excalibur-llm",
    "excalibur-ingest",
    "excalibur-api",
    "excalibur-bench",
    "excalibur-integration"
)
