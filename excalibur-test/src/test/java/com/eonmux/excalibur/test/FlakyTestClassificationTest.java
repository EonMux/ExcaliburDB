package com.eonmux.excalibur.test;

import com.eonmux.excalibur.test.FlakyTestAnalyzer.ConsistentFailure;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.FlakyTestReport;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.FlakyTestResult;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.RunSummary;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.TestRunResult;
import com.eonmux.excalibur.test.FlakyTestAnalyzer.TestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the analyzer concludes from a set of runs that already happened.
 *
 * <p>The verdict - flaky, consistently failing, or sound - is the analyzer's whole output,
 * and a wrong one either hides a test that fails one time in five or sends someone chasing a
 * test that never failed. These drive the classification with fabricated runs, because the
 * alternative is launching Gradle five times to find out what the code decides.</p>
 */
@DisplayName("Flaky test classification")
class FlakyTestClassificationTest {

    private static final String MODULE = "excalibur-integration";

    @Test
    @DisplayName("a test that passes sometimes and fails sometimes is reported flaky")
    void intermittentTestIsFlaky() {
        FlakyTestReport report = classify(
            run(1, passed("SearchTest.findsMatch")),
            run(2, failed("SearchTest.findsMatch", "expected 3 but was 2")),
            run(3, passed("SearchTest.findsMatch")),
            run(4, failed("SearchTest.findsMatch", "expected 3 but was 2")),
            run(5, passed("SearchTest.findsMatch")));

        assertThat(report.getFlakyTests()).hasSize(1);
        FlakyTestResult flaky = report.getFlakyTests().get(0);
        assertThat(flaky.testName()).isEqualTo("SearchTest.findsMatch");
        assertThat(flaky.passCount()).isEqualTo(3);
        assertThat(flaky.failCount()).isEqualTo(2);
        assertThat(flaky.totalRuns()).isEqualTo(5);
        assertThat(report.getConsistentFailures()).isEmpty();
        assertThat(report.getAlwaysPassing()).isEmpty();
    }

    @Test
    @DisplayName("a test that fails every run is a consistent failure, not a flaky one")
    void alwaysFailingTestIsNotFlaky() {
        FlakyTestReport report = classify(
            run(1, failed("BuildTest.compiles", "cannot find symbol")),
            run(2, failed("BuildTest.compiles", "cannot find symbol")),
            run(3, failed("BuildTest.compiles", "cannot find symbol")));

        assertThat(report.getFlakyTests())
            .as("a test that never passes is broken, and calling it flaky sends someone "
                + "looking for a race that is not there")
            .isEmpty();
        assertThat(report.getConsistentFailures())
            .extracting(ConsistentFailure::testName)
            .containsExactly("BuildTest.compiles");
        assertThat(report.getConsistentFailures().get(0).errorMessage())
            .isEqualTo("cannot find symbol");
    }

    @Test
    @DisplayName("a test that passes every run is reported sound")
    void alwaysPassingTestIsSound() {
        FlakyTestReport report = classify(
            run(1, passed("HashTest.hashes")),
            run(2, passed("HashTest.hashes")),
            run(3, passed("HashTest.hashes")));

        assertThat(report.getAlwaysPassing()).containsExactly("HashTest.hashes");
        assertThat(report.getFlakyTests()).isEmpty();
        assertThat(report.getConsistentFailures()).isEmpty();
    }

    @Test
    @DisplayName("a test skipped in every run is reported neither way")
    void skippedEverywhereIsNoEvidence() {
        FlakyTestReport report = classify(
            run(1, skipped("SlowTest.needsNetwork")),
            run(2, skipped("SlowTest.needsNetwork")),
            run(3, skipped("SlowTest.needsNetwork")));

        assertThat(report.getFlakyTests()).isEmpty();
        assertThat(report.getConsistentFailures()).isEmpty();
        assertThat(report.getAlwaysPassing())
            .as("a test that never ran is not a test that passed")
            .isEmpty();
    }

    @Test
    @DisplayName("runs that skipped a test are left out of its pass rate")
    void skippedRunsDoNotDiluteThePassRate() {
        FlakyTestReport report = classify(
            run(1, passed("MixedTest.sometimes")),
            run(2, skipped("MixedTest.sometimes")),
            run(3, failed("MixedTest.sometimes", "timeout")),
            run(4, skipped("MixedTest.sometimes")));

        FlakyTestResult flaky = report.getFlakyTests().get(0);
        assertThat(flaky.totalRuns())
            .as("only the runs that reached a verdict count; counting skips would make a "
                + "50/50 test look like a 25%% failure and drop it below the threshold")
            .isEqualTo(2);
        assertThat(flaky.passRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the flakiest test is reported first")
    void flakiestFirst() {
        FlakyTestReport report = classify(
            run(1, passed("A.evenSplit"), passed("B.rarelyFails")),
            run(2, failed("A.evenSplit", "boom"), passed("B.rarelyFails")),
            run(3, passed("A.evenSplit"), passed("B.rarelyFails")),
            run(4, failed("A.evenSplit", "boom"), failed("B.rarelyFails", "boom")));

        assertThat(report.getFlakyTests())
            .extracting(FlakyTestResult::testName)
            .as("triage starts at the top of the list, so the worst offender belongs there")
            .containsExactly("A.evenSplit", "B.rarelyFails");
        assertThat(report.getFlakyTests().get(0).flakinessScore())
            .isGreaterThan(report.getFlakyTests().get(1).flakinessScore());
    }

    @Test
    @DisplayName("a consistent failure is categorised so it can be routed")
    void consistentFailuresAreCategorised() {
        FlakyTestReport report = classify(
            run(1, failed("NetTest.connects", "java.net.ConnectException: Connection refused")),
            run(2, failed("NetTest.connects", "java.net.ConnectException: Connection refused")),
            run(3, failed("NetTest.connects", "java.net.ConnectException: Connection refused")));

        assertThat(report.getCategorizedFailures())
            .containsKey("NetTest.connects");
        assertThat(report.getCategorizedFailures().get("NetTest.connects"))
            .isEqualTo(FailureCategorizer.categorize(
                "NetTest.connects", "java.net.ConnectException: Connection refused"));
    }

    @Test
    @DisplayName("the report carries the runs it was drawn from")
    void reportCarriesItsEvidence() {
        FlakyTestReport report = classify(
            run(1, passed("A.one")),
            run(2, failed("A.one", "boom")),
            run(3, passed("A.one")));

        assertThat(report.getModuleName()).isEqualTo(MODULE);
        assertThat(report.getRunCount()).isEqualTo(3);
        assertThat(report.getRunSummaries()).hasSize(3);
        assertThat(report.getAllResults().get("A.one")).hasSize(3);
    }

    private static FlakyTestReport classify(RunSummary... runs) {
        return FlakyTestAnalyzer.classify(MODULE, List.of(runs), false);
    }

    private static RunSummary run(int number, TestRunResult... results) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        List<TestRunResult> numbered = new ArrayList<>();
        for (TestRunResult result : results) {
            numbered.add(new TestRunResult(
                result.testName(), result.status(), result.errorMessage(), number));
            switch (result.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        return new RunSummary(number, numbered, passed, failed, skipped,
            Duration.ofSeconds(1), failed == 0 ? 0 : 1);
    }

    private static TestRunResult passed(String name) {
        return new TestRunResult(name, TestStatus.PASSED, null, 1);
    }

    private static TestRunResult failed(String name, String message) {
        return new TestRunResult(name, TestStatus.FAILED, message, 1);
    }

    private static TestRunResult skipped(String name) {
        return new TestRunResult(name, TestStatus.SKIPPED, null, 1);
    }
}
