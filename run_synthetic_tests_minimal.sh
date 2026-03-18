#!/bin/bash
# Minimal standalone test runner for SyntheticTestGenerator
# This script runs only the core tests without requiring VerificationReport

cd /home/aeslampanah/Desktop/Projects/ExcaliburDB

echo "========================================"
echo "SyntheticTestGenerator - Minimal Tests"
echo "========================================"
echo ""

# Find required jar files
CORE_JAR="/home/aeslampanah/Desktop/Projects/ExcaliburDB/excalibur-core/build/libs/excalibur-core-1.0.0-SNAPSHOT.jar"
JUNIT_JARS=$(find ~/.gradle/caches -name "junit-jupiter-api-*.jar" 2>/dev/null | head -1)
JUNIT_VINTAGE=$(find ~/.gradle/caches -name "junit-jupiter-engine-*.jar" 2>/dev/null | head -1)

if [ -z "$CORE_JAR" ]; then
    echo "ERROR: Cannot find excalibur-core jar"
    exit 1
fi

if [ -z "$JUNIT_JARS" ]; then
    echo "ERROR: Cannot find junit-jupiter jar"
    exit 1
fi

if [ -z "$JUNIT_VINTAGE" ]; then
    echo "ERROR: Cannot find junit-vintage-engine jar"
    exit 1
fi

# Compile only the essential files
mkdir -p test-classes

echo "Compiling SyntheticTestGeneratorTest..."

# Build classpath properly
classpath="$CORE_JAR:$JUNIT_JARS:$JUNIT_VINTAGE"

# Start the javac command
javac --module-path "$CORE_JAR" \
      -cp "$classpath" \
      -d "test-classes" \
      excalibur-test/src/main/java/excalibur/test/SyntheticTestGenerator.java \
      excalibur-core/src/main/java/excalibur/core/schema/Document.java \
      excalibur-core/src/main/java/excalibur/core/schema/Query.java \
      excalibur-core/src/main/java/excalibur/core/schema/QueryResult.java \
      excalibur-core/src/main/java/excalibur/core/schema/QueryResultEntry.java \
      excalibur-test/src/test/java/excalibur/test/SyntheticTestGeneratorTest.java 2>&1

if [ $? -ne 0 ]; then
    echo "Compilation failed"
    exit 1
fi

echo "Running tests..."
java -cp "test-classes:$classpath" excalibur.test.SyntheticTestGeneratorTest
exit $?
