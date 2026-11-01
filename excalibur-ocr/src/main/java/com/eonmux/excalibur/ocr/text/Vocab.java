// Ported from Indra OCR 1.1.2, vocab.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.json.Json;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A line recognizer's token vocabulary: what each of its output classes reads as.
 *
 * <p>Class 0 is the CTC blank and class {@code i + 1} is token {@code i}. A token is one
 * character (one code point), or in a vocabulary with math one LaTeX command such as
 * {@code \frac} or {@code \alpha}. A vocabulary that holds the Hangul leading consonant U+1100
 * reads Korean as jamo, which decoding composes back into syllables.</p>
 *
 * <p>Instances are immutable. Port of the inference half of {@code vocab.py}'s {@code Vocab}:
 * reading one from its JSON form and decoding class ids; the training half (building character
 * sets, tokenizing and encoding labels) is not used by inference and is not ported.</p>
 */
public final class Vocab {

    /** The Hangul leading consonant whose presence marks a vocabulary that reads jamo. */
    private static final String JAMO_MARKER = "ᄀ";

    private final List<String> chars;
    private final Map<String, Integer> index;
    private final boolean hasCommands;
    private final boolean jamo;

    private Vocab(List<String> chars) {
        this.chars = List.copyOf(chars);
        Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < this.chars.size(); i++) {
            ids.put(this.chars.get(i), i + 1);
        }
        this.index = Collections.unmodifiableMap(ids);
        this.hasCommands = this.chars.stream().anyMatch(c -> c.codePointCount(0, c.length()) > 1);
        this.jamo = ids.containsKey(JAMO_MARKER);
    }

    /**
     * A vocabulary of the given tokens. Port of {@code Vocab.__init__}.
     *
     * @param chars The tokens, in class order: token {@code i} is class {@code i + 1}. A token
     *              listed twice is decoded from both classes, and {@link #index} maps it to the
     *              later one, as Python's dict does.
     * @return The vocabulary
     */
    public static Vocab of(List<String> chars) {
        Objects.requireNonNull(chars, "chars must not be null");
        for (int i = 0; i < chars.size(); i++) {
            if (chars.get(i) == null) {
                throw new IllegalArgumentException("token " + i + " is null");
            }
        }
        return new Vocab(chars);
    }

    /**
     * Reads a vocabulary from its JSON form, {@code {"blank": 0, "chars": [...]}}, as
     * {@code meta.json} holds it (as a string) under {@code vocab} and {@code vocab_<group>}.
     * Port of {@code Vocab.from_json}.
     *
     * @param json The JSON text
     * @return The vocabulary
     * @throws IllegalArgumentException if the text is not such an object, or its blank class is
     *                                  not 0
     */
    public static Vocab fromJson(String json) {
        Map<String, Object> d = Json.parseObject(json);
        Object blank = d.getOrDefault("blank", 0L);
        if (!(blank instanceof Number n) || n.doubleValue() != 0) {
            throw new IllegalArgumentException("only blank=0 vocabularies are supported, got "
                + blank);
        }
        if (!(d.get("chars") instanceof List<?> list)) {
            throw new IllegalArgumentException("a vocabulary needs a \"chars\" array, got "
                + Json.describe(d.get("chars")));
        }
        List<String> chars = new ArrayList<>(list.size());
        for (Object token : list) {
            if (!(token instanceof String s)) {
                throw new IllegalArgumentException("vocabulary tokens are strings, got "
                    + Json.describe(token) + " at " + chars.size());
            }
            chars.add(s);
        }
        return new Vocab(chars);
    }

    /**
     * The tokens in class order: token {@code i} is class {@code i + 1}.
     *
     * @return An unmodifiable list
     */
    public List<String> chars() {
        return chars;
    }

    /**
     * Each token's class id. Port of {@code Vocab.index}.
     *
     * @return An unmodifiable map from token to class (1 and up)
     */
    public Map<String, Integer> index() {
        return index;
    }

    /**
     * One token's class id, as Python's {@code vocab.index.get(token)} answers.
     *
     * @param token The token
     * @return Its class, or empty if the vocabulary does not hold it
     */
    public OptionalInt indexOf(String token) {
        Integer id = index.get(token);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /**
     * The number of recognizer output classes, the blank included. Port of
     * {@code Vocab.n_classes}.
     *
     * @return The token count plus one
     */
    public int nClasses() {
        return chars.size() + 1;
    }

    /**
     * Whether any token is longer than one code point, that is, a LaTeX command. Port of
     * {@code Vocab.has_commands}.
     *
     * @return Whether the vocabulary holds commands
     */
    public boolean hasCommands() {
        return hasCommands;
    }

    /**
     * Whether the vocabulary reads Hangul as jamo, composed into syllables on decoding. Port of
     * {@code Vocab.jamo}.
     *
     * @return Whether it holds U+1100
     */
    public boolean jamo() {
        return jamo;
    }

    /**
     * The text a sequence of class ids reads as. Port of {@code Vocab.decode}.
     *
     * <p>Ids outside {@code 1..chars().size()} - the blank and anything out of range - are
     * skipped. In a jamo vocabulary the text is NFC normalized, composing syllables. In a
     * vocabulary with commands, a space follows a letter command ({@code \alpha}) when a letter
     * comes next, so it does not read back as a longer command ({@code \alphax}).</p>
     *
     * @param ids The class ids
     * @return The text
     */
    public String decode(int[] ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        List<String> tokens = new ArrayList<>(ids.length);
        for (int id : ids) {
            if (id > 0 && id <= chars.size()) {
                tokens.add(chars.get(id - 1));
            }
        }
        if (jamo) {
            return Normalizer.normalize(String.join("", tokens), Normalizer.Form.NFC);
        }
        if (!hasCommands) {
            return String.join("", tokens);
        }
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < tokens.size(); k++) {
            String token = tokens.get(k);
            out.append(token);
            String next = k + 1 < tokens.size() ? tokens.get(k + 1) : "";
            if (isLetterCommand(token) && startsWithLetter(next)) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /**
     * Canonicalizes a label as the vocabulary's training targets were. Port of
     * {@code Vocab.normalize}.
     *
     * @param text The text
     * @return {@link Canonical#canonical(String)} of it
     */
    public String normalize(String text) {
        return Canonical.canonical(text);
    }

    /**
     * Python's {@code len(t) > 2 and t[0] == "\\" and t[1:].isalpha()}: a backslash and then two
     * or more letters.
     */
    private static boolean isLetterCommand(String token) {
        return token.codePointCount(0, token.length()) > 2 && token.charAt(0) == '\\'
            && UnicodeData.isAlpha(token.substring(1));
    }

    /** Python's {@code s[:1].isalpha()}: whether the first code point is a letter. */
    private static boolean startsWithLetter(String text) {
        return !text.isEmpty() && Character.isLetter(text.codePointAt(0));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vocab other && chars.equals(other.chars);
    }

    @Override
    public int hashCode() {
        return chars.hashCode();
    }

    @Override
    public String toString() {
        return "Vocab[" + chars.size() + " tokens" + (hasCommands ? ", commands" : "")
            + (jamo ? ", jamo" : "") + "]";
    }
}
