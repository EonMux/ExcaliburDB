package excalibur.bench.calibration;

import java.util.List;

/**
 * One question whose right answer is known.
 *
 * <p>The answer is named rather than numbered. An entity id is what the graph works in, but it is
 * an artefact of whichever build assigned it, so a judgement file written against ids is invalid
 * the moment the graph is rebuilt - and invalid silently, because the old ids still resolve to
 * something. Names survive a rebuild, and the harness resolves them through the same linker the
 * pipeline resolves the question's own mentions with.
 *
 * <p>Several names, because a question can have more than one right answer and scoring one of two
 * correct entities as wrong would fit thresholds against a precision the system never actually
 * lacked.
 *
 * @param question the question, exactly as a caller would ask it
 * @param expected the names of the entities that would answer it
 */
public record JudgedQuestion(String question, List<String> expected) {

    public JudgedQuestion {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException(
                "a judged question needs at least one expected entity; a question with no known "
                    + "answer cannot tell a fit whether an answer was right");
        }
        expected = List.copyOf(expected);
    }
}
