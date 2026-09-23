package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads facts out of a document by asking the chat model for them.
 *
 * <h2>Why a model rather than a parser</h2>
 * <p>The facts a document asserts are in its prose, and no pattern over capitalised words recovers
 * them: "Ada Lovelace worked with Charles Babbage" and "Babbage's collaborator, Lovelace," are the
 * same fact written two ways. A model is the only extractor that reads the second one. What the
 * model is not allowed to do is decide anything else - the temperature is zero, the reply format is
 * fixed, and every triple it returns is checked before it becomes a fact.</p>
 *
 * <h2>What is checked</h2>
 * <p>A model asked for JSON will sometimes return prose, a fourth field, an empty subject, or a
 * relation phrased as a sentence. Each of those is dropped rather than stored: a graph is only
 * worth traversing if an edge means what it says, and one malformed reply must not put a paragraph
 * where a relation name belongs. A reply that yields nothing readable at all is an
 * {@link ExtractionException} - the document was examined and the answer was lost, and recording
 * that it asserted nothing would leave a gap no later run would notice.</p>
 *
 * <h2>Dates</h2>
 * <p>The model is asked for the dates a fact held over only where the text states them - "joined
 * Acme in March 2019", "was chair until 2021" - and never to estimate one. A fact whose text gives
 * no date is valid over all time as far as this extractor knows, which is the truth about what it
 * read. When a later document gives a single-valued relation a different value, the graph
 * builder orders the two by these dates where they exist and by the documents' own dates where
 * they do not, which the ingestion side records; guessing here would put an invented date ahead
 * of a real one.</p>
 *
 * <p>A date is taken at the precision the text gave it. A start is the first day of the period
 * named - "2019" begins on 1 January 2019 - and an end, which the model reports as the last period
 * the fact held in, becomes the first day after that period, which is how an interval's end is
 * stored. A date that does not parse is dropped and the fact kept; an end that does not fall
 * after its start drops both, because one of them is wrong and nothing says which.</p>
 *
 * <h2>Cost</h2>
 * <p>One call per document, over a bounded prefix of its text. Extraction is the most expensive
 * thing ingestion can do, which is why it is off unless a deployment asks for it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless beyond its collaborators, all of which are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class LlmRelationExtractor implements RelationExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmRelationExtractor.class);

    /**
     * How much of a document is shown to the model.
     *
     * <p>A prefix, not a summary: the facts a document states are overwhelmingly in its opening,
     * and sending a whole book would cost more than the graph it produced is worth.
     */
    public static final int DEFAULT_MAX_INPUT_CHARS = 8000;

    /** Longest label this extractor will accept back from the model. */
    public static final int MAX_LABEL_CHARS = 200;

    /** Most facts one document may contribute, however many the model returns. */
    public static final int DEFAULT_MAX_TRIPLES = 64;

    /** Confidence recorded when the model does not report one of its own. */
    public static final double DEFAULT_CONFIDENCE = 0.5;

    private static final String INSTRUCTION =
        "You extract factual relationships from text. Reply with a JSON array and nothing else. "
        + "Each element is {\"subject\":\"...\",\"relation\":\"...\",\"object\":\"...\","
        + "\"confidence\":0.0}. Use the shortest form of each entity's proper name as it appears "
        + "in the text. Use a short lower_snake_case verb phrase for the relation. Give confidence "
        + "between 0 and 1 for how clearly the text states the fact. When the text states when a "
        + "fact began to hold, add \"valid_from\" with that date; when it states the last date the "
        + "fact held, add \"valid_to\" with that date. Write a date as YYYY-MM-DD, or as YYYY-MM "
        + "or YYYY when the text is only that precise, and leave both out when the text gives no "
        + "date for the fact. Extract only what the text actually asserts; never infer a fact or "
        + "a date. Reply with [] if it asserts no relationships.";

    /** One <code>{...}</code> object of the reply. */
    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");

    /** A string field of one object. */
    private static final Pattern FIELD = Pattern.compile(
        "\"(subject|relation|object)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** A date field of one object, which the model omits when the text gives no date. */
    private static final Pattern DATE = Pattern.compile(
        "\"(valid_from|valid_to)\"\\s*:\\s*\"([^\"]*)\"");

    /** A date at the precision a text states one: a year, a month or a day. */
    private static final Pattern DATE_VALUE = Pattern.compile(
        "(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?");

    /** The confidence field, which the model may omit. */
    private static final Pattern CONFIDENCE = Pattern.compile(
        "\"confidence\"\\s*:\\s*([0-9]*\\.?[0-9]+)");

    private final LLMHttpClient llmClient;
    private final int extractorVersion;
    private final int maxInputChars;
    private final int maxTriples;

    /**
     * Creates an extractor over the chat model.
     *
     * @param llmClient        The client to ask
     * @param extractorVersion Recorded on every fact, so a bad extractor's output can be erased by
     *                         version later
     */
    public LlmRelationExtractor(LLMHttpClient llmClient, int extractorVersion) {
        this(llmClient, extractorVersion, DEFAULT_MAX_INPUT_CHARS, DEFAULT_MAX_TRIPLES);
    }

    /**
     * Creates an extractor with explicit bounds.
     *
     * @param llmClient        The client to ask
     * @param extractorVersion Recorded on every fact
     * @param maxInputChars    How much of a document to show the model
     * @param maxTriples       Most facts one document may contribute
     */
    public LlmRelationExtractor(LLMHttpClient llmClient, int extractorVersion,
                                int maxInputChars, int maxTriples) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        if (extractorVersion < Short.MIN_VALUE || extractorVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "extractorVersion must fit in a short, got " + extractorVersion);
        }
        if (maxInputChars <= 0) {
            throw new IllegalArgumentException("maxInputChars must be positive");
        }
        if (maxTriples <= 0) {
            throw new IllegalArgumentException("maxTriples must be positive");
        }
        this.extractorVersion = extractorVersion;
        this.maxInputChars = maxInputChars;
        this.maxTriples = maxTriples;
    }

    /**
     * The circuit breaker this extractor's calls are made through.
     *
     * <p>Exposed so the node can report it. When it opens, every document is still chunked,
     * embedded and indexed, and none of them contributes a fact to the graph - ingestion
     * reports success throughout, so without this a graph that has stopped growing looks the
     * same as a corpus with nothing more to say.</p>
     *
     * @return The breaker guarding relation extraction
     */
    public CircuitBreaker getCircuitBreaker() {
        return llmClient.getCircuitBreaker();
    }

    @Override
    public List<ExtractedTriple> extract(String documentKey, String text) {
        if (documentKey == null || documentKey.isBlank()) {
            throw new IllegalArgumentException("documentKey must not be null or blank");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String input = text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;
        String reply;
        try {
            reply = llmClient.completeWithCircuitBreaker(INSTRUCTION, input, documentKey);
        } catch (LLMException e) {
            throw new ExtractionException(
                "The chat model could not be asked for the facts in " + documentKey, e);
        }

        List<ExtractedTriple> triples = parse(reply, documentKey);
        LOGGER.debug("extracted {} facts from {}", triples.size(), documentKey);
        return triples;
    }

    /**
     * Reads the triples out of a reply, dropping anything malformed.
     *
     * @param reply       What the model said
     * @param documentKey The document, for provenance and for the error message
     * @return The facts that survived checking
     */
    private List<ExtractedTriple> parse(String reply, String documentKey) {
        if (reply == null || reply.isBlank()) {
            throw new ExtractionException("The chat model returned nothing for " + documentKey);
        }
        String trimmed = reply.trim();

        long sourceId = sourceIdOf(documentKey);
        List<ExtractedTriple> triples = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean sawAnyObject = false;

        Matcher objects = OBJECT.matcher(trimmed);
        while (objects.find() && triples.size() < maxTriples) {
            sawAnyObject = true;
            String object = objects.group();

            String subjectLabel = null;
            String relationLabel = null;
            String objectLabel = null;
            Matcher fields = FIELD.matcher(object);
            while (fields.find()) {
                String value = unescape(fields.group(2));
                switch (fields.group(1)) {
                    case "subject" -> subjectLabel = value;
                    case "relation" -> relationLabel = value;
                    case "object" -> objectLabel = value;
                    default -> { }
                }
            }

            String subject = normaliseEntity(subjectLabel);
            String relation = normaliseRelation(relationLabel);
            String target = normaliseEntity(objectLabel);
            if (subject == null || relation == null || target == null) {
                LOGGER.debug("dropping a malformed triple from {}: {}", documentKey, object);
                continue;
            }
            if (subject.equalsIgnoreCase(target)) {
                // A fact relating a thing to itself carries no relationship, and the serializer
                // spends budget describing the entity twice for it.
                continue;
            }
            if (!seen.add(subject + ' ' + relation + ' ' + target)) {
                continue;
            }

            int[] interval = intervalOf(object, documentKey);
            triples.add(new ExtractedTriple(subject, relation, target, interval[0], interval[1],
                Provenance.of(SourceType.DOCUMENT, sourceId, confidenceOf(object, documentKey),
                    extractorVersion)));
        }

        if (!sawAnyObject && !trimmed.startsWith("[")) {
            throw new ExtractionException(
                "The chat model's reply for " + documentKey + " held no readable triples: "
                    + trimmed.substring(0, Math.min(200, trimmed.length())));
        }
        return List.copyOf(triples);
    }

    /**
     * The interval one returned object states, as {@code [validFrom, validTo)} in epoch days.
     *
     * <p>{@link TemporalFilter#UNDATED} and {@link TemporalFilter#OPEN} for a bound the text did
     * not state, or stated in a form that does not parse. Both are dropped when the end does not
     * fall after the start: the two bounds contradict each other, and keeping either one alone
     * would be choosing which half of the model's answer to believe.
     *
     * @param object      The object text
     * @param documentKey The document, for the log line
     * @return The two bounds
     */
    private static int[] intervalOf(String object, String documentKey) {
        int validFrom = TemporalFilter.UNDATED;
        int validTo = TemporalFilter.OPEN;
        Matcher dates = DATE.matcher(object);
        while (dates.find()) {
            boolean start = "valid_from".equals(dates.group(1));
            Integer day = dayOf(dates.group(2), start);
            if (day == null) {
                LOGGER.debug("unreadable {} in {}: {}", dates.group(1), documentKey,
                    dates.group(2));
            } else if (start) {
                validFrom = day;
            } else {
                validTo = day;
            }
        }
        if (validFrom >= validTo) {
            LOGGER.debug("dropping the dates of a fact in {} whose end is not after its start: {}",
                documentKey, object);
            return new int[] { TemporalFilter.UNDATED, TemporalFilter.OPEN };
        }
        return new int[] { validFrom, validTo };
    }

    /**
     * One bound of an interval, from a date at whatever precision the text gave.
     *
     * @param text  The date as the model wrote it
     * @param start Whether it is the start - the first day of the period named - or the last
     *              period the fact held in, whose bound is the first day after it
     * @return The bound in epoch days, or null if the text is not a date
     */
    private static Integer dayOf(String text, boolean start) {
        Matcher date = DATE_VALUE.matcher(text.strip());
        if (!date.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(date.group(1));
            LocalDate first;
            LocalDate afterLast;
            if (date.group(3) != null) {
                first = LocalDate.of(year, Integer.parseInt(date.group(2)),
                    Integer.parseInt(date.group(3)));
                afterLast = first.plusDays(1);
            } else if (date.group(2) != null) {
                first = LocalDate.of(year, Integer.parseInt(date.group(2)), 1);
                afterLast = first.plusMonths(1);
            } else {
                first = LocalDate.of(year, 1, 1);
                afterLast = first.plusYears(1);
            }
            return Math.toIntExact((start ? first : afterLast).toEpochDay());
        } catch (DateTimeException e) {
            // A month thirteen or a thirty-first of February: shaped like a date and not one.
            return null;
        }
    }

    /**
     * The confidence one returned object reports, or the default when it reports none.
     *
     * @param object      The object text
     * @param documentKey The document, for the log line
     * @return A confidence in [0, 1]
     */
    private static double confidenceOf(String object, String documentKey) {
        Matcher confidence = CONFIDENCE.matcher(object);
        if (!confidence.find()) {
            return DEFAULT_CONFIDENCE;
        }
        try {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(confidence.group(1))));
        } catch (NumberFormatException e) {
            // Keep the default rather than dropping a fact over how sure the model said it was.
            LOGGER.debug("unreadable confidence in {}: {}", documentKey, confidence.group(1));
            return DEFAULT_CONFIDENCE;
        }
    }

    /**
     * An entity label the graph can use, or null if the model returned something that is not one.
     *
     * @param label What the model said
     * @return The cleaned label, or null
     */
    private static String normaliseEntity(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_LABEL_CHARS) {
            return null;
        }
        return cleaned;
    }

    /**
     * A relation label, lower-cased so that "Worked With" and "worked_with" are one relation.
     *
     * <p>Relations are a controlled vocabulary in everything but name: a graph in which the same
     * relationship appears under three spellings cannot be traversed by relation at all.
     *
     * @param label What the model said
     * @return The cleaned label, or null
     */
    private static String normaliseRelation(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (cleaned.isEmpty() || cleaned.length() > MAX_LABEL_CHARS) {
            return null;
        }
        return cleaned;
    }

    /**
     * A stable numeric id for a document key, for the provenance column.
     *
     * <p>Stable across runs, so an erasure request naming a document resolves to the same edges it
     * did when they were written.
     *
     * <p>Public because an erasure has to be able to ask the same question from the other end.
     * Every fact and every snippet this node stages from a document carries this id, and nothing
     * else records which document a fact came from - so withdrawing a document means submitting
     * a source-scoped obligation for exactly this number. A second definition of it somewhere
     * else would be a second answer the day either one changed, and the symptom would be an
     * erasure that reported success and removed nothing.
     *
     * @param documentKey The document key, as
     *                    {@link com.eonmux.excalibur.core.text.ChunkIdentifiers#documentKey} forms it
     * @return Its id
     */
    public static long sourceIdOf(String documentKey) {
        return com.eonmux.excalibur.store.hash.XxHash64.hash(documentKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Undoes the JSON escapes a label may carry.
     *
     * @param value The raw captured value
     * @return The decoded value
     */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
