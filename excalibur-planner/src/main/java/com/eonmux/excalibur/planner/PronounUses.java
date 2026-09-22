package com.eonmux.excalibur.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tells a pronoun that points at something from a pronoun-shaped word that points at nothing.
 *
 * <p>"that", "it", "this" and "those" are only sometimes pronouns. In "who founded the company that
 * Ada worked at" the "that" opens a relative clause; in "is it true that Ada worked with Babbage"
 * the "it" is a placeholder subject and the "that" introduces what is true. Neither names anything,
 * so neither has an antecedent to find - and treating them as though they did turns an ordinary,
 * fully specified question into a request that the user say who "that" is. The question back is
 * then worse than useless: there is no right answer to it.
 *
 * <h2>How it decides</h2>
 * By the words around the pronoun, not by parsing. Each rule recognises a construction whose
 * pronoun-shaped word is structural - a clause opening after it, a complement-taking word before
 * it, a predicate like "true" followed by what it is true of - and anything no rule recognises
 * stays a referring pronoun. That default is deliberate and matches the resolver it serves: a
 * construction this misses costs one question back, which was the behaviour before any of it
 * existed; a referring pronoun this wrongly waved through would bind to nothing and retrieve for a
 * question with its subject missing.
 *
 * <h2>What it is not</h2>
 * Not a grammar. It covers the constructions that dominate questions put to a knowledge graph -
 * relative clauses, complement clauses, extraposition, clefts, time deixis, generic "those who",
 * tag questions - and makes no claim beyond them.
 */
final class PronounUses {

    /**
     * Words that open a clause with its own subject, or that are one.
     *
     * <p>A "that" followed by one of these is introducing a clause - "the company that the board
     * sold", "said that he left" - because a demonstrative "that" is followed by the noun it
     * modifies or by nothing at all, never by a determiner or a second subject.
     */
    private static final Set<String> CLAUSE_OPENERS = Set.of(
        "the", "a", "an", "this", "these", "those", "his", "her", "its", "their", "my", "our",
        "your", "he", "she", "it", "they", "we", "i", "you", "there", "someone", "somebody",
        "something", "no", "not", "every", "each", "all", "some", "any", "many", "most", "both");

    /** Auxiliaries and forms of "be", which follow a relative or complementizer "that" freely. */
    private static final Set<String> AUXILIARIES = Set.of(
        "is", "was", "are", "were", "be", "been", "being", "am", "has", "have", "had", "do",
        "does", "did", "can", "could", "will", "would", "shall", "should", "may", "might", "must");

    /** Forms of "be", for recognising extraposition and clefts around "it". */
    private static final Set<String> BE_FORMS = Set.of(
        "is", "was", "are", "were", "be", "been", "being", "am");

    /**
     * Words that take a "that" clause as their complement.
     *
     * <p>Verbs of saying and knowing, the adjectives and nouns that take a clause, and the
     * connectives that end in "that". What follows any of these is the content of the saying, not
     * a thing pointed at. Verbs that as readily take a plain object - "found", "showed", "heard" -
     * are left out, because "who found that company" is a demonstrative and the list cannot tell
     * the two apart.
     */
    private static final Set<String> COMPLEMENT_TAKERS = Set.of(
        "say", "says", "said", "claim", "claims", "claimed", "think", "thinks", "thought",
        "believe", "believes", "believed", "know", "knows", "knew", "report", "reports",
        "reported", "announce", "announces", "announced", "state", "states", "stated", "prove",
        "proves", "proved", "suggest", "suggests", "suggested", "mean", "means", "meant",
        "realise", "realised", "realize", "realized", "learn", "learned", "learnt", "assume",
        "assumed", "confirm", "confirmed", "deny", "denied", "argue", "argued", "true", "false",
        "fact", "idea", "news", "evidence", "sure", "certain", "likely", "possible", "clear",
        "aware", "so", "such", "now", "given", "except", "provided", "assuming");

    /** Determiners, whose presence two words back marks the word between as a noun. */
    private static final Set<String> DETERMINERS = Set.of(
        "the", "a", "an", "every", "each", "any", "some", "no", "another", "which", "what",
        "whose");

    /**
     * Nouns a demonstrative can pick out a time with.
     *
     * <p>"That year", "this week", "at that time": deixis into the calendar, not a reference to an
     * entity, and the graph's valid-time handling is what reads them - there is nothing for an
     * entity antecedent to add.
     */
    private static final Set<String> TIME_NOUNS = Set.of(
        "year", "years", "month", "months", "week", "weeks", "day", "days", "morning",
        "afternoon", "evening", "night", "time", "times", "century", "decade", "quarter",
        "season", "era", "period", "moment", "point");

    /** Predicates an extraposed "it" stands in front of: "it is true that", "possible to". */
    private static final Set<String> EXTRAPOSITION_PREDICATES = Set.of(
        "true", "false", "possible", "impossible", "likely", "unlikely", "known", "clear",
        "certain", "uncertain", "necessary", "important", "obvious", "evident", "surprising",
        "hard", "easy", "fair", "correct", "right", "wrong", "case");

    /** What follows an extraposition predicate: the clause the "it" stands in for. */
    private static final Set<String> EXTRAPOSED_CLAUSE_OPENERS = Set.of(
        "that", "to", "whether", "if", "who", "when", "where", "why", "how", "what");

    /** Verbs that take a placeholder "it" as subject: "it seems that", "it turned out". */
    private static final Set<String> RAISING_VERBS = Set.of(
        "seem", "seems", "seemed", "appear", "appears", "appeared", "happen", "happens",
        "happened", "turn", "turns", "turned");

    /** Verbs of cost and duration that extrapose an infinitive: "how long did it take to". */
    private static final Set<String> DURATION_VERBS = Set.of(
        "take", "takes", "took", "cost", "costs");

    /** Relative words that make "those" generic: "those who worked with Ada". */
    private static final Set<String> GENERIC_FOLLOWERS = Set.of(
        "who", "whom", "which", "that", "whose");

    /** How far past "it" an extraposed clause is looked for, in words. */
    private static final int EXTRAPOSITION_WINDOW = 4;

    private PronounUses() {
    }

    /**
     * One word of a question, or a boundary between phrases.
     *
     * @param text   the word as written; empty for a boundary
     * @param offset where it starts in the question
     * @param kind   what sort of token it is
     */
    record Token(String text, int offset, Kind kind) {

        /** The word lower-cased, for matching against the vocabularies above. */
        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        boolean is(Kind wanted) {
            return kind == wanted;
        }
    }

    /** What sort of token a {@link Token} is. */
    enum Kind {
        /** An ordinary word. */
        WORD,

        /**
         * A name: a capitalised word after the first, or a quoted span.
         *
         * <p>Never a pronoun, even when it is spelled like one - {@code "It"} in quotes is a
         * title and "That" mid-sentence is somebody's name - and a clause subject when it follows
         * "that".
         */
        NAME,

        /** Punctuation that ends a phrase, or the end of the question. */
        BOUNDARY
    }

    /**
     * Splits a question into words, names and phrase boundaries.
     *
     * <p>A quoted span becomes one name token, so nothing inside it is ever read as a pronoun: it
     * is a title or a verbatim name, and "who directed \"That Thing You Do\"" has nothing in it
     * to resolve.
     *
     * @param text the question
     * @return its tokens, always ending in a boundary
     */
    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                int close = text.indexOf('"', i + 1);
                int end = close < 0 ? text.length() : close + 1;
                tokens.add(new Token(text.substring(i, end), i, Kind.NAME));
                i = end;
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < text.length()
                        && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '\'')) {
                    i++;
                }
                String word = text.substring(start, i);
                boolean capitalisedAfterFirst = !tokens.isEmpty()
                    && !tokens.get(tokens.size() - 1).is(Kind.BOUNDARY)
                    && Character.isUpperCase(word.charAt(0));
                tokens.add(new Token(word, start, capitalisedAfterFirst ? Kind.NAME : Kind.WORD));
            } else {
                if (",.;:?!()".indexOf(c) >= 0) {
                    tokens.add(new Token("", i, Kind.BOUNDARY));
                }
                i++;
            }
        }
        tokens.add(new Token("", text.length(), Kind.BOUNDARY));
        return tokens;
    }

    /**
     * Whether the pronoun-shaped word at {@code index} refers to something.
     *
     * @param tokens the question, as {@link #tokenize} split it
     * @param index  the position of a word the resolver recognises as a pronoun
     * @return false when the word is doing a structural job and names nothing
     */
    static boolean refers(List<Token> tokens, int index) {
        if (tag(tokens, index)) {
            return false;
        }
        return switch (tokens.get(index).lower()) {
            case "that" -> !structuralThat(tokens, index);
            case "it" -> !expletiveIt(tokens, index);
            case "this", "these" -> !isTimeNoun(tokens, index + 1);
            case "those" -> !isTimeNoun(tokens, index + 1)
                && !lowerIn(tokens, index + 1, GENERIC_FOLLOWERS);
            default -> true;
        };
    }

    /**
     * Whether a "that" is a relative pronoun, a complementizer or time deixis.
     *
     * <p>Checked in order of how little each rule can get wrong. Nothing after it, or only the end
     * of a phrase, is the demonstrative pronoun - "tell me more about that" - and is settled first
     * so no later rule can wave it through.
     */
    private static boolean structuralThat(List<Token> tokens, int index) {
        Token next = tokens.get(index + 1);
        if (next.is(Kind.BOUNDARY)) {
            return false;
        }
        if (isTimeNoun(tokens, index + 1)) {
            return true;
        }
        // "said that he left", "true that", "so that": whatever follows is the content of the
        // clause. Unless it is a single word ending the phrase - "did Ada know that man" - which
        // is the demonstrative and its noun.
        if (lowerIn(tokens, index - 1, COMPLEMENT_TAKERS)
                && !(next.is(Kind.WORD) && tokens.get(index + 2).is(Kind.BOUNDARY))) {
            return true;
        }
        // A clause with its own subject or auxiliary starts here. A demonstrative is followed by
        // its noun, and a name or a second determiner cannot be that noun.
        if (next.is(Kind.NAME) || CLAUSE_OPENERS.contains(next.lower())
                || AUXILIARIES.contains(next.lower())) {
            return true;
        }
        // "Babbage that designed it": straight after a name, "that" opens a clause about the
        // name. A demonstrative there would be pointing past a thing the question just named.
        if (index >= 1 && tokens.get(index - 1).is(Kind.NAME)) {
            return true;
        }
        // "the company that hired Ada": a determiner two back makes the word between a noun, and
        // a "that" straight after a noun opens a clause about it rather than pointing elsewhere.
        return index >= 2
            && tokens.get(index - 1).is(Kind.WORD)
            && !AUXILIARIES.contains(tokens.get(index - 1).lower())
            && (lowerIn(tokens, index - 2, DETERMINERS) || isPossessiveName(tokens.get(index - 2)));
    }

    /**
     * Whether an "it" is a placeholder subject rather than a reference.
     *
     * <p>Three constructions, each recognised by what the "it" is waiting for: a predicate followed
     * by the clause it applies to ("is it true that"), a raising verb with its complement ("it
     * seems that", "it turned out"), and a cleft or duration that hands its content to a clause
     * later on ("was it Ada who", "how long did it take to build"). An "it" followed by none of
     * them - "who founded it", "when was it published" - is the thing being asked about.
     */
    private static boolean expletiveIt(List<Token> tokens, int index) {
        if (cleft(tokens, index)) {
            return true;
        }
        for (int offset = 1; offset <= EXTRAPOSITION_WINDOW; offset++) {
            int at = index + offset;
            Token token = tokens.get(at);
            if (token.is(Kind.BOUNDARY)) {
                return false;
            }
            String word = token.lower();
            if (token.is(Kind.WORD) && EXTRAPOSITION_PREDICATES.contains(word)
                    && lowerIn(tokens, at + 1, EXTRAPOSED_CLAUSE_OPENERS)) {
                return true;
            }
            if (offset == 1 && RAISING_VERBS.contains(word)
                    && followedWithin(tokens, at, Set.of("that", "to", "out", "as", "like"))) {
                return true;
            }
            if (offset == 1 && DURATION_VERBS.contains(word) && infinitiveAfter(tokens, at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an "it" is the placeholder of a cleft.
     *
     * <p>"Was it Ada who wrote Note G", "it was Babbage that designed it", "when was it that she
     * left": "be" beside the "it", then a name and the clause about it, or the clause at once.
     * Narrower than any "who" or "that" further along on purpose - "is it the company that Ada
     * founded" asks whether some earlier thing is that company, and its "it" does refer.
     */
    private static boolean cleft(List<Token> tokens, int index) {
        Set<String> clauseMarkers = Set.of("who", "that");
        if (lowerIn(tokens, index - 1, BE_FORMS)) {
            return lowerIn(tokens, index + 1, clauseMarkers)
                || lowerIn(tokens, afterName(tokens, index + 1), clauseMarkers);
        }
        return lowerIn(tokens, index + 1, BE_FORMS)
            && lowerIn(tokens, afterName(tokens, index + 2), clauseMarkers);
    }

    /**
     * The position after the name starting at {@code index}, or -1 if no name starts there.
     *
     * <p>A name can run to several capitalised words - "Charles Babbage" - and what matters is
     * what follows all of them.
     */
    private static int afterName(List<Token> tokens, int index) {
        if (index >= tokens.size() || !tokens.get(index).is(Kind.NAME)) {
            return -1;
        }
        int at = index;
        while (at < tokens.size() && tokens.get(at).is(Kind.NAME)) {
            at++;
        }
        return at;
    }

    /**
     * Whether the pronoun closes a tag question: "Babbage designed it, didn't he?"
     *
     * <p>A tag repeats the subject of the question it is attached to, so it names nothing the
     * question has not already named - and asking who "he" is there asks the user to point at a
     * subject they have just stated.
     */
    private static boolean tag(List<Token> tokens, int index) {
        if (index < 2 || !tokens.get(index + 1).is(Kind.BOUNDARY)
                || !tokens.get(index - 2).is(Kind.BOUNDARY)) {
            return false;
        }
        Token auxiliary = tokens.get(index - 1);
        return auxiliary.is(Kind.WORD) && (AUXILIARIES.contains(auxiliary.lower())
            || auxiliary.lower().endsWith("n't"));
    }

    /** Whether one of {@code words} appears within the window after {@code index}. */
    private static boolean followedWithin(List<Token> tokens, int index, Set<String> words) {
        for (int at = index + 1; at <= index + EXTRAPOSITION_WINDOW && at < tokens.size(); at++) {
            if (tokens.get(at).is(Kind.BOUNDARY)) {
                return false;
            }
            if (words.contains(tokens.get(at).lower())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a "to" followed by a verb comes within the window after {@code index}.
     *
     * <p>A "to" followed by a determiner, a name or nothing is a preposition - "did it belong to
     * the Crown", "who did it sell to" - and the "it" before it is the thing that belonged.
     */
    private static boolean infinitiveAfter(List<Token> tokens, int index) {
        int last = Math.min(index + EXTRAPOSITION_WINDOW, tokens.size() - 2);
        for (int at = index + 1; at <= last; at++) {
            Token token = tokens.get(at);
            if (token.is(Kind.BOUNDARY)) {
                return false;
            }
            if ("to".equals(token.lower())) {
                Token after = tokens.get(at + 1);
                return after.is(Kind.WORD) && !CLAUSE_OPENERS.contains(after.lower());
            }
        }
        return false;
    }

    private static boolean isTimeNoun(List<Token> tokens, int index) {
        return index < tokens.size() && tokens.get(index).is(Kind.WORD)
            && TIME_NOUNS.contains(tokens.get(index).lower());
    }

    private static boolean isPossessiveName(Token token) {
        return token.is(Kind.NAME) && token.lower().endsWith("'s");
    }

    private static boolean lowerIn(List<Token> tokens, int index, Set<String> words) {
        return index >= 0 && index < tokens.size() && !tokens.get(index).is(Kind.BOUNDARY)
            && words.contains(tokens.get(index).lower());
    }
}
