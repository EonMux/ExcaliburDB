package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Guards the sanitizer against model output that costs more to inspect than it is worth.
 *
 * <p>The sanitizer reads whatever the provider sent, and the only thing bounding that is the
 * HTTP client's response cap - tens of megabytes. Anything superlinear in the input length is
 * therefore a denial of service reachable by anyone who can make the node call its model, so
 * the cost of scanning is asserted here rather than left to a reviewer's reading of the
 * pattern.</p>
 *
 * <p>Timing is asserted through a timeout rather than a measured elapsed time: a wall-clock
 * threshold turns every loaded CI machine into a spurious failure, while a timeout distinguishes
 * a linear scan from a quadratic one by orders of magnitude and never by a few percent.</p>
 */
class ResponseSanitizerBoundsTest {

    /** Long enough that a quadratic scan takes tens of seconds, short enough to stay under the cap. */
    private static final int PATHOLOGICAL_LENGTH = 150_000;

    /** Comfortably longer than a linear scan of the above needs, far shorter than a quadratic one. */
    private static final Duration BUDGET = Duration.ofSeconds(2);

    private final ResponseSanitizer sanitizer = new ResponseSanitizer();

    @Test
    @DisplayName("a wall of unclosed brackets is scanned in bounded time")
    void unclosedBracketsDoNotBacktrack() {
        String hostile = "[".repeat(PATHOLOGICAL_LENGTH);

        assertTimeoutPreemptively(BUDGET, () -> assertNull(sanitizer.sanitize(hostile),
            "A response with no closing bracket carries no array"));
    }

    @Test
    @DisplayName("opening brackets followed by prose are scanned in bounded time")
    void interleavedBracketsDoNotBacktrack() {
        String hostile = "[ text ".repeat(PATHOLOGICAL_LENGTH / 7);

        assertTimeoutPreemptively(BUDGET, () -> assertNull(sanitizer.sanitize(hostile),
            "A response with no closing bracket carries no array"));
    }

    @Test
    @DisplayName("a fenced wall of unclosed brackets is scanned in bounded time")
    void fencedHostileInputDoesNotBacktrack() {
        String hostile = "```json\n" + "[".repeat(PATHOLOGICAL_LENGTH) + "\n";

        assertTimeoutPreemptively(BUDGET, () -> assertNull(sanitizer.sanitize(hostile),
            "A response with no closing bracket carries no array"));
    }

    @Test
    @DisplayName("a response too large to be a score array is refused rather than searched")
    void oversizedResponseIsRefused() {
        // Benign content: the point is the length, not the shape. A reply this size is not an
        // answer to "return only a JSON array of scores", whatever is buried in it.
        String oversized = "x".repeat(4 * 1024 * 1024) + "[{\"index\": 0, \"score\": 0.5}]";

        assertTimeoutPreemptively(BUDGET, () -> assertNull(sanitizer.sanitize(oversized),
            "A response past the sanitizer's input cap must be refused, not mined for an array"));
    }

    @Test
    @DisplayName("a large but plausible response is still read")
    void largeButPlausibleResponseIsStillRead() {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            if (i > 0) {
                array.append(',');
            }
            array.append("{\"index\": ").append(i).append(", \"score\": 0.5}");
        }
        array.append(']');

        String result = assertTimeoutPreemptively(BUDGET, () -> sanitizer.sanitize(array.toString()));

        assertNotNull(result, "A score array for a large batch is exactly what this must accept");
        assertEquals(array.toString(), result);
    }

    @Test
    @DisplayName("a bracket inside a JSON string does not close the array")
    void bracketInsideStringDoesNotTerminateTheArray() {
        String input = "[{\"text\": \"a ] b\", \"score\": 0.5}]";

        assertEquals(input, sanitizer.sanitize(input),
            "Closing on a bracket inside a string truncates the array mid-object");
    }
}
