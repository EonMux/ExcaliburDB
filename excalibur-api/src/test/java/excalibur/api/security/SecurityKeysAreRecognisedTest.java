package excalibur.api.security;

import io.excalibur.config.ConfigKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds this module's property keys against the config module's inventory of what it recognises.
 *
 * <p>{@code ConfigLoader} warns at startup that a key it does not recognise is being ignored, but
 * it can only judge the prefixes it owns. Most of the security settings live under
 * {@code excalibur.api.}, which it deliberately does not claim - but three of them sit under
 * {@code excalibur.ingest.}, which it does, and two of those are the allow-lists that decide which
 * directories may be crawled and which hosts may be fetched. A warning saying one of those is
 * ignored is worse than no warning: an operator who acts on it deletes the line, and the empty
 * allow-list that results silently stops ingestion working.</p>
 *
 * <p>Reflective rather than a written-out list, because a written-out list is the thing that
 * drifted.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("Security property keys")
class SecurityKeysAreRecognisedTest {

    @Test
    @DisplayName("every key under a prefix the config module owns is one it recognises")
    void everyOwnedKeyIsKnown() {
        List<String> unrecognised = new ArrayList<>();
        for (String key : propertyKeysOf(ApiSecurityConfig.class, TlsConfig.class)) {
            if (ConfigKeys.isOwned(key) && !ConfigKeys.isKnown(key)) {
                unrecognised.add(key);
            }
        }

        assertThat(unrecognised)
            .as("a key this module reads, under a prefix the config module judges, must be in "
                + "that module's inventory - otherwise startup warns that a setting which is "
                + "being honoured is being ignored")
            .isEmpty();
    }

    /** Every {@code public static final String KEY_*} declared by the given classes. */
    private static List<String> propertyKeysOf(Class<?>... classes) {
        List<String> keys = new ArrayList<>();
        for (Class<?> type : classes) {
            for (Field field : type.getDeclaredFields()) {
                if (isPropertyKey(field)) {
                    keys.add(readKey(field));
                }
            }
        }
        assertThat(keys).as("the reflection found no keys at all, so it is testing nothing")
            .isNotEmpty();
        return keys;
    }

    private static boolean isPropertyKey(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers)
            && Modifier.isStatic(modifiers)
            && Modifier.isFinal(modifiers)
            && field.getType() == String.class
            && field.getName().startsWith("KEY_");
    }

    private static String readKey(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read " + field.getName(), e);
        }
    }
}
