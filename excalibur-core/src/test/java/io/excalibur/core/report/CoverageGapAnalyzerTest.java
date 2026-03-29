package io.excalibur.core.report;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import io.excalibur.core.report.CoverageGapAnalyzer.ClassGap;
import io.excalibur.core.report.CoverageGapAnalyzer.MethodGap;
import io.excalibur.core.report.CoverageGapAnalyzer.ModuleGapAnalysis;
import io.excalibur.core.report.CoverageGapAnalyzer.PackageGap;

/**
 * Tests for {@link CoverageGapAnalyzer}.
 *
 * <p>Thread Safety: Each test is independent and operates on isolated test data.</p>
 */
class CoverageGapAnalyzerTest {

    private static final String SAMPLE_JACOCO_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
        <report name="test-module">
            <package name="com/example/pkg1">
                <class name="com/example/pkg1/ClassA" sourcefilename="ClassA.java">
                    <method name="&lt;init&gt;" desc="()V" line="10">
                        <counter type="INSTRUCTION" missed="0" covered="10"/>
                        <counter type="LINE" missed="0" covered="3"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="coveredMethod" desc="()V" line="20">
                        <counter type="INSTRUCTION" missed="0" covered="20"/>
                        <counter type="LINE" missed="0" covered="5"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="uncoveredMethod" desc="()V" line="30">
                        <counter type="INSTRUCTION" missed="15" covered="0"/>
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="COMPLEXITY" missed="1" covered="0"/>
                        <counter type="METHOD" missed="1" covered="0"/>
                    </method>
                    <counter type="INSTRUCTION" missed="15" covered="30"/>
                    <counter type="BRANCH" missed="2" covered="4"/>
                    <counter type="LINE" missed="4" covered="8"/>
                    <counter type="COMPLEXITY" missed="1" covered="2"/>
                    <counter type="METHOD" missed="1" covered="2"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                </class>
                <counter type="INSTRUCTION" missed="15" covered="30"/>
                <counter type="BRANCH" missed="2" covered="4"/>
                <counter type="LINE" missed="4" covered="8"/>
                <counter type="COMPLEXITY" missed="1" covered="2"/>
                <counter type="METHOD" missed="1" covered="2"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </package>
            <package name="com/example/pkg2">
                <class name="com/example/pkg2/ClassB" sourcefilename="ClassB.java">
                    <method name="&lt;init&gt;" desc="()V" line="5">
                        <counter type="INSTRUCTION" missed="0" covered="5"/>
                        <counter type="LINE" missed="0" covered="2"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="fullyCovered" desc="()I" line="15">
                        <counter type="INSTRUCTION" missed="0" covered="10"/>
                        <counter type="LINE" missed="0" covered="3"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <counter type="INSTRUCTION" missed="0" covered="15"/>
                    <counter type="BRANCH" missed="0" covered="2"/>
                    <counter type="LINE" missed="0" covered="5"/>
                    <counter type="COMPLEXITY" missed="0" covered="2"/>
                    <counter type="METHOD" missed="0" covered="2"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                </class>
                <counter type="INSTRUCTION" missed="0" covered="15"/>
                <counter type="BRANCH" missed="0" covered="2"/>
                <counter type="LINE" missed="0" covered="5"/>
                <counter type="COMPLEXITY" missed="0" covered="2"/>
                <counter type="METHOD" missed="0" covered="2"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </package>
            <counter type="INSTRUCTION" missed="15" covered="45"/>
            <counter type="BRANCH" missed="2" covered="6"/>
            <counter type="LINE" missed="4" covered="13"/>
            <counter type="COMPLEXITY" missed="1" covered="4"/>
            <counter type="METHOD" missed="1" covered="4"/>
            <counter type="CLASS" missed="0" covered="2"/>
        </report>
        """;

    @TempDir
    Path tempDir;

    private Path jacocoReportPath;

    @BeforeEach
    void setUp() throws IOException {
        jacocoReportPath = tempDir.resolve("jacocoTestReport.xml");
        Files.writeString(jacocoReportPath, SAMPLE_JACOCO_XML);
    }

    @Test
    void analyzeModule_shouldIdentifyClassesBelowThreshold() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        assertThat(analysis.moduleName()).isEqualTo("test-module");
        assertThat(analysis.moduleCoverage()).isGreaterThan(0.0);

        // ClassA has 33% uncovered (15/45 instructions), so it should be flagged
        assertThat(analysis.classesBelowThreshold())
            .hasSize(1)
            .first()
            .satisfies(cls -> {
                assertThat(cls.className()).isEqualTo("ClassA");
                assertThat(cls.instructionCoverage()).isCloseTo(66.67, within(0.01)); // 30/(30+15) = 66.67%
            });
    }

    @Test
    void analyzeModule_shouldIdentifyUncoveredMethods() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        assertThat(analysis.methodsWithZeroCoverage())
            .hasSize(1)
            .first()
            .satisfies(method -> {
                assertThat(method.methodName()).isEqualTo("uncoveredMethod");
                assertThat(method.lineNumber()).isEqualTo(30);
                assertThat(method.missedInstructions()).isEqualTo(15);
            });
    }

    @Test
    void analyzeModule_shouldIdentifyPackagesBelowThreshold() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        // pkg1 has 66.67% coverage (30/45 instructions), below 80%
        assertThat(analysis.packagesBelowThreshold())
            .hasSize(1)
            .first()
            .satisfies(pkg -> {
                assertThat(pkg.packageName()).isEqualTo("com.example.pkg1");
                assertThat(pkg.coverage()).isCloseTo(66.67, within(0.01));
                assertThat(pkg.classCount()).isEqualTo(1);
            });
    }

    @Test
    void analyzeModule_shouldCalculateCorrectModuleCoverage() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        // Total: 45 covered, 15 missed = 75% coverage
        assertThat(analysis.moduleCoverage()).isEqualTo(75.0);
    }

    @Test
    void generateTextSummary_shouldProduceReadableOutput() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");
        Map<String, ModuleGapAnalysis> results = Map.of("test-module", analysis);

        String summary = CoverageGapAnalyzer.generateTextSummary(results);

        assertThat(summary)
            .contains("COVERAGE GAP ANALYSIS REPORT")
            .contains("MODULE: test-module")
            .contains("Packages Below 80% Threshold")
            .contains("Classes Below 80% Threshold")
            .contains("Methods With Zero Coverage")
            .contains("uncoveredMethod")
            .contains("SUMMARY");
    }

    @Test
    void classGap_shouldProvideAccessToCoverageMetrics() {
        ClassGap gap = new ClassGap(
            "com.example",
            "TestClass",
            50.0,  // instruction
            40.0,  // branch
            60.0,  // line
            List.of(),
            List.of()
        );

        assertThat(gap.packageName()).isEqualTo("com.example");
        assertThat(gap.className()).isEqualTo("TestClass");
        assertThat(gap.instructionCoverage()).isEqualTo(50.0);
        assertThat(gap.branchCoverage()).isEqualTo(40.0);
        assertThat(gap.lineCoverage()).isEqualTo(60.0);
        assertThat(gap.uncoveredMethods()).isEmpty();
        assertThat(gap.uncoveredLines()).isEmpty();
    }

    @Test
    void methodGap_shouldProvideAccessToDetails() {
        MethodGap gap = new MethodGap(
            "com.example.TestClass",
            "testMethod",
            42,
            100
        );

        assertThat(gap.className()).isEqualTo("com.example.TestClass");
        assertThat(gap.methodName()).isEqualTo("testMethod");
        assertThat(gap.lineNumber()).isEqualTo(42);
        assertThat(gap.missedInstructions()).isEqualTo(100);
    }

    @Test
    void packageGap_shouldProvideAccessToDetails() {
        PackageGap gap = new PackageGap(
            "com.example.subpackage",
            55.5,
            10
        );

        assertThat(gap.packageName()).isEqualTo("com.example.subpackage");
        assertThat(gap.coverage()).isEqualTo(55.5);
        assertThat(gap.classCount()).isEqualTo(10);
    }

    @Test
    void moduleGapAnalysis_shouldAggregateAllGapTypes() {
        ModuleGapAnalysis analysis = new ModuleGapAnalysis(
            "test-module",
            List.of(new ClassGap("pkg", "cls", 50.0, 40.0, 30.0, List.of(), List.of())),
            List.of(new MethodGap("pkg.cls", "method", 10, 50)),
            List.of(new PackageGap("pkg", 60.0, 1)),
            70.0
        );

        assertThat(analysis.moduleName()).isEqualTo("test-module");
        assertThat(analysis.classesBelowThreshold()).hasSize(1);
        assertThat(analysis.methodsWithZeroCoverage()).hasSize(1);
        assertThat(analysis.packagesBelowThreshold()).hasSize(1);
        assertThat(analysis.moduleCoverage()).isEqualTo(70.0);
    }

    @Test
    void analyzeModule_shouldHandleEmptyReport() throws IOException {
        String emptyXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="empty-module">
                <counter type="INSTRUCTION" missed="0" covered="0"/>
                <counter type="BRANCH" missed="0" covered="0"/>
                <counter type="LINE" missed="0" covered="0"/>
                <counter type="METHOD" missed="0" covered="0"/>
                <counter type="CLASS" missed="0" covered="0"/>
            </report>
            """;

        Path emptyReportPath = tempDir.resolve("emptyReport.xml");
        Files.writeString(emptyReportPath, emptyXml);

        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(emptyReportPath, "empty-module");

        assertThat(analysis.moduleName()).isEqualTo("empty-module");
        // Domain correctness: No code to cover means 0% coverage (nothing was covered)
        assertThat(analysis.moduleCoverage()).isEqualTo(0.0);
        assertThat(analysis.classesBelowThreshold()).isEmpty();
        assertThat(analysis.methodsWithZeroCoverage()).isEmpty();
    }
}
