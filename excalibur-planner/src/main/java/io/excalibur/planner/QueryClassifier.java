package io.excalibur.planner;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.excalibur.graph.temporal.TemporalFilter;

/**
 * Turns a question into a {@link QueryPlan}, deterministically and without a model.
 *
 * <p>The same question always produces the same plan. That is worth more here than sophistication:
 * a plan that varies run to run makes every downstream measurement noisy, and a wrong plan that can
 * be traced to a rule is one someone can fix in a minute.
 *
 * <h2>How it decides</h2>
 * Lexical cues, in a fixed order of precedence, because the categories overlap — "how many people
 * did she work with in 2019" is aggregate, traversal and temporal at once. Temporal is applied as a
 * wrapper so it composes with whichever of the other three won; among those three, aggregation
 * beats traversal beats lookup, on the principle that the more specific plan does strictly less
 * work.
 *
 * <h2>What it does not do</h2>
 * It does not parse. It recognises the phrasings that dominate real questions and falls back to a
 * lookup otherwise — which retrieves less than an ideal plan would, but retrieves something
 * relevant rather than something wrong. Entity extraction is likewise lexical: capitalised runs and
 * quoted spans. Full entity linking happens later, against the graph.
 */
public final class QueryClassifier {

    private static final CueSet AGGREGATE_CUES = CueSet.of(
        "how many", "how much", "count of", "number of", "total number");

    private static final CueSet EARLIEST_CUES = CueSet.of(
        "first", "earliest", "oldest", "originally");

    private static final CueSet LATEST_CUES = CueSet.of(
        "latest", "most recent", "last", "currently", "now");

    /**
     * Phrasings that imply the answer is reachable from an entity rather than stored on it.
     *
     * <p>Deliberately relational: "who did X work with" needs an edge walk, while "when was X born"
     * does not, and the difference is worth a hop of traversal cost either way.
     */
    private static final CueSet TRAVERSE_CUES = CueSet.of(
        "who did", "worked with", "connected to", "related to", "associated with",
        "collaborated", "influenced", "linked to", "part of", "member of",
        "co-author", "coauthor", "path between", "relationship between", "between");

    /**
     * Phrasings that ask what held at some past instant.
     *
     * <p>Past tense alone is deliberately absent. "When was Ada Lovelace born" is past tense and is
     * not a temporal question — it asks for a fact that has one value for all time. Treating bare
     * "was" as a cue makes almost every question temporal, which drags the whole history of every
     * edge into answers that wanted the current value. What marks a genuinely temporal question is
     * an explicit instant, or a phrase that contrasts then with now.
     */
    private static final CueSet TEMPORAL_CUES = CueSet.of(
        "as of", "at the time", "back in", "used to", "formerly",
        "previously", "in the past", "historically", "at that time", "back then");

    /**
     * {@code in 1998}, {@code as of 2019}, {@code during 2004}, {@code before 1843}.
     *
     * <p>The preposition is captured, because it decides what instant the year names: "in 1843"
     * asks about a point inside the year, "before 1843" about one at or ahead of its start, and
     * "since 1843" about an open interval that names no point at all.
     */
    private static final Pattern YEAR = Pattern.compile(
        "\\b(in|as of|during|by|before|after|since)\\s+(1[6-9]\\d{2}|20\\d{2})\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * ISO dates.
     *
     * <p>The month and day ranges are part of the pattern so that a part number, invoice id or
     * phone number of the same shape is not taken for a date. Combinations the calendar still
     * rejects - a 31st of February, a 29th of a common February - get past the pattern and are
     * rejected when the date is constructed.
     */
    private static final Pattern ISO_DATE = Pattern.compile(
        "\\b(\\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b");

    /** A run of capitalised words, or anything in quotes. */
    private static final Pattern MENTION = Pattern.compile(
        "\"([^\"]+)\"|\\b([A-Z][\\w'-]*(?:\\s+[A-Z][\\w'-]*)*)\\b");

    /** Capitalised words that start sentences and are not entities. */
    private static final Set<String> STOP_MENTIONS = Set.of(
        "who", "what", "when", "where", "why", "how", "which", "whose", "whom",
        "did", "does", "do", "is", "are", "was", "were", "the", "a", "an", "and", "or",
        "tell", "show", "list", "find", "give");

    private final CoreferenceResolver coreference;

    public QueryClassifier() {
        this(new CoreferenceResolver());
    }

    public QueryClassifier(CoreferenceResolver coreference) {
        if (coreference == null) {
            throw new IllegalArgumentException("coreference must not be null");
        }
        this.coreference = coreference;
    }

    /** Classifies a question with no conversation behind it. */
    public PlanningOutcome classify(String question) {
        return classify(question, new ConversationContext());
    }

    /**
     * Classifies a question, resolving its pronouns against {@code context}.
     *
     * <p>Coreference runs first. A question whose pronouns cannot be bound has no determinate
     * subject, so there is nothing to classify — and classifying it anyway would produce a plan
     * aimed at whatever entity happened to be extracted.
     */
    public PlanningOutcome classify(String question, ConversationContext context) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        CoreferenceResolver.Resolution resolution = coreference.resolve(question, context);
        if (!resolution.isResolved()) {
            return resolution.ask();
        }

        List<String> mentions = new ArrayList<>(extractMentions(question));
        mentions.addAll(resolution.boundMentions());
        List<String> distinct = List.copyOf(new LinkedHashSet<>(mentions));

        if (distinct.isEmpty()) {
            return new PlanningOutcome.AskUser(
                PlanningOutcome.Reason.NO_ENTITY_MENTION,
                "Which person, place or thing should I look up?",
                List.of(), List.of());
        }

        QueryPlan core = classifyCore(question.toLowerCase(Locale.ROOT), distinct);
        TemporalFilter asOf = temporalFilterOf(question);
        return new PlanningOutcome.Planned(
            asOf == null ? core : new QueryPlan.Temporal(core, asOf));
    }

    /**
     * Chooses between aggregate, traverse and lookup.
     *
     * <p>Most specific first: an aggregate that is also relational still only needs a count, and
     * planning it as a traversal would materialise neighbours to count them.
     */
    private QueryPlan classifyCore(String lower, List<String> mentions) {
        List<String> relationHints = relationHints(lower);

        if (AGGREGATE_CUES.matches(lower)) {
            return new QueryPlan.Aggregate(
                mentions, QueryPlan.AggregateFunction.COUNT, relationHints);
        }
        if (EARLIEST_CUES.matches(lower)) {
            return new QueryPlan.Aggregate(
                mentions, QueryPlan.AggregateFunction.EARLIEST, relationHints);
        }
        if (LATEST_CUES.matches(lower)) {
            return new QueryPlan.Aggregate(
                mentions, QueryPlan.AggregateFunction.LATEST, relationHints);
        }
        if (TRAVERSE_CUES.matches(lower)) {
            // Two entities named together is a question about what connects them, which needs
            // room to meet in the middle; one entity needs only its immediate neighbourhood.
            int hops = mentions.size() > 1 ? 2 : 1;
            return new QueryPlan.Traverse(mentions, hops, relationHints);
        }
        return new QueryPlan.Lookup(mentions);
    }

    /**
     * The valid-time instant the question asks about, or null if it asks about now.
     *
     * <p>Always valid time. The index's epoch is a different axis entirely, and nothing in a user's
     * question can refer to it.
     */
    private TemporalFilter temporalFilterOf(String question) {
        LocalDate isoDate = firstIsoDate(question);
        if (isoDate != null) {
            return TemporalFilter.asOf(isoDate);
        }
        Matcher year = YEAR.matcher(question);
        if (year.find()) {
            return yearFilter(year.group(1), Integer.parseInt(year.group(2)));
        }
        if (TEMPORAL_CUES.matches(question.toLowerCase(Locale.ROOT))) {
            // The question is about the past but names no instant. Answering as of now would
            // contradict it, and guessing an instant would invent one, so ask for the whole
            // history and let ranking present it.
            return TemporalFilter.ALL;
        }
        return null;
    }

    /**
     * The first ISO date in the question that the calendar accepts, or null if there is none.
     *
     * <p>A digit run that looks like a date but is not one - a 2024-13-01 part number - is
     * skipped rather than allowed to abort the classification. {@code LocalDate.of} throws
     * {@link java.time.DateTimeException}, which is neither an {@link IllegalArgumentException}
     * nor anything the endpoint maps to a client error, so letting it escape turns a
     * well-formed question into a 500.
     *
     * @param question The question
     * @return The date, or null
     */
    private static LocalDate firstIsoDate(String question) {
        Matcher matcher = ISO_DATE.matcher(question);
        while (matcher.find()) {
            try {
                return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            } catch (DateTimeException e) {
                // Not a date after all - keep looking.
            }
        }
        return null;
    }

    /**
     * The instant a year names, given the preposition that introduced it.
     *
     * <p>Three different questions were being answered identically. "In 1843" asks what held
     * during the year, and mid-year admits any interval covering part of it. "Before 1843" asks
     * what held ahead of the year, so an edge that started in March 1843 must not qualify.
     * "Since 1843" and "after 1843" name an interval rather than an instant, and there is no
     * point-in-time that stands for one - answering as of any single day inside it silently
     * drops everything that began later.
     *
     * @param preposition The preposition that introduced the year
     * @param year The year
     * @return The filter for that reading
     */
    private static TemporalFilter yearFilter(String preposition, int year) {
        return switch (preposition.toLowerCase(Locale.ROOT)) {
            case "before" -> TemporalFilter.asOf(LocalDate.of(year, 1, 1));
            case "by" -> TemporalFilter.asOf(LocalDate.of(year, 12, 31));
            case "after", "since" -> TemporalFilter.ALL;
            default -> TemporalFilter.asOf(LocalDate.of(year, 7, 1));
        };
    }

    /** Relation-like words the question uses, as hints for the traversal's whitelist. */
    private List<String> relationHints(String lower) {
        return TRAVERSE_CUES.matchesIn(lower);
    }

    /**
     * Entity mentions: quoted spans, and runs of capitalised words.
     *
     * <p>Crude, and honest about it. It over-produces on sentence-initial capitals — filtered by a
     * stop list — and under-produces on lowercase entities. Linking against the graph is what
     * settles which of these are real.
     */
    private Set<String> extractMentions(String question) {
        Set<String> mentions = new LinkedHashSet<>();
        Matcher matcher = MENTION.matcher(question);
        while (matcher.find()) {
            String quoted = matcher.group(1);
            String capitalised = matcher.group(2);
            String candidate = quoted != null ? quoted : capitalised;
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            candidate = candidate.trim();
            if (quoted == null && STOP_MENTIONS.contains(candidate.toLowerCase(Locale.ROOT))) {
                continue;
            }
            mentions.add(candidate);
        }
        return mentions;
    }

    /**
     * A set of lexical cues matched on token boundaries.
     *
     * <p>Raw substring containment is what made "know" a cue for "now" and "plastic" a cue for
     * "last", so any question containing "known" was planned as a latest-value aggregate. The
     * cues keep their declaration order, so the hints a plan carries do not depend on a set's
     * iteration order - which for {@code Set.of} varies between runs of the same JVM, and would
     * make the same question produce a different plan each time.
     */
    private static final class CueSet {

        private final List<String> cues;
        private final List<Pattern> patterns;

        private CueSet(List<String> cues, List<Pattern> patterns) {
            this.cues = cues;
            this.patterns = patterns;
        }

        static CueSet of(String... cues) {
            List<String> ordered = List.of(cues);
            List<Pattern> compiled = new ArrayList<>(ordered.size());
            for (String cue : ordered) {
                compiled.add(compile(cue));
            }
            return new CueSet(ordered, List.copyOf(compiled));
        }

        /**
         * Compiles one cue into a whole-token matcher.
         *
         * <p>Internal spaces become {@code \s+} so a cue survives the whitespace a question
         * actually contains, and each word is quoted so a cue with punctuation in it -
         * "co-author" - is matched literally.
         */
        private static Pattern compile(String cue) {
            StringBuilder regex = new StringBuilder("\\b");
            String[] words = cue.trim().split("\\s+");
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    regex.append("\\s+");
                }
                regex.append(Pattern.quote(words[i]));
            }
            return Pattern.compile(regex.append("\\b").toString(), Pattern.CASE_INSENSITIVE);
        }

        boolean matches(String text) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(text).find()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The cues present in {@code text}, in declaration order.
         *
         * @param text The text to scan
         * @return The matching cues
         */
        List<String> matchesIn(String text) {
            List<String> found = new ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).matcher(text).find()) {
                    found.add(cues.get(i));
                }
            }
            return found;
        }
    }
}
