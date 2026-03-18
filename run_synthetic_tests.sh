#!/bin/bash
# Standalone test runner for SyntheticTestGenerator
# This script runs the test without requiring gradle or complex dependencies

cd /home/aeslampanah/Desktop/Projects/ExcaliburDB

echo "========================================"
echo "SyntheticTestGenerator - Standalone Tests"
echo "========================================"
echo ""

# Find required jar files
CORE_JAR="/home/aeslampanah/Desktop/Projects/ExcaliburDB/excalibur-core/build/libs/excalibur-core-1.0.0-SNAPSHOT.jar"
JUNIT_JARS=$(find ~/.gradle/caches -name "junit-jupiter-api-*.jar" 2>/dev/null | head -1)
JUNIT_VINTAGE=$(find ~/.gradle/caches -name "junit-vintage-engine-*.jar" 2>/dev/null | head -1)

if [ -z "$CORE_JAR" ]; then
    echo "ERROR: Cannot find excalibur-core jar"
    echo "Please run 'gradle build' first to compile the project"
    exit 1
fi

if [ -z "$JUNIT_JARS" ]; then
    echo "ERROR: Cannot find junit-jupiter jar"
    exit 1
fi

# Compile the test class
mkdir -p test-classes

echo "Compiling SyntheticTestGeneratorTest..."

# Collect all source files needed for compilation
cat > compile_args.txt << EOF
--module-path "$CORE_JAR"
-cp "$JUNIT_JARS:$JUNIT_VINTAGE"
-d "test-classes"
EOF

# Add synthetic test generator files
echo "excalibur-test/src/main/java/excalibur/test/SyntheticTestGenerator.java" >> compile_args.txt
echo "excalibur-test/src/main/java/excalibur/test/VerificationReport.java" >> compile_args.txt
echo "excalibur-test/src/main/java/excalibur/test/TestResult.java" >> compile_args.txt
echo "excalibur-test/src/main/java/excalibur/test/TestEntry.java" >> compile_args.txt

# Add core schema files
echo "excalibur-core/src/main/java/excalibur/core/schema/Document.java" >> compile_args.txt
echo "excalibur-core/src/main/java/excalibur/core/schema/Query.java" >> compile_args.txt
echo "excalibur-core/src/main/java/excalibur/core/schema/QueryResult.java" >> compile_args.txt
echo "excalibur-core/src/main/java/excalibur/core/schema/QueryResultEntry.java" >> compile_args.txt

# Add the test file
echo "excalibur-test/src/test/java/excalibur/test/SyntheticTestGeneratorTest.java" >> compile_args.txt

# Compile
javac $(cat compile_args.txt) 2>&1

if [ $? -ne 0 ]; then
    echo "Compilation failed"
    cat compile_args.txt
    exit 1
fi

echo "Running tests..."
java -cp "test-classes:$CORE_JAR:$JUNIT_JARS:$JUNIT_VINTAGE" excalibur.test.SyntheticTestGeneratorTest
exit $?
