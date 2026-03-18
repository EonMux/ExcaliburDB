#!/bin/sh

##############################################################################
##
##  Gradle start up script for UN*X
##
##  This script is for running on the target JVM.
##  It passes the arguments to the target JVM.
##
##############################################################################

# Attempt to set APP_HOME
APP_HOME="${PWD}"

# Attempt to set GRADLE_OPTS
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx512m"

# Execute the Gradle JVM
exec /home/aeslampanah/Desktop/Projects/ExcaliburDB/gradle-8.11/bin/gradle "$@"
