package com.eonmux.excalibur.api.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SourceConfigView")
class SourceConfigViewTest {

    @Test
    @DisplayName("quotes every setting a registration can carry, withholding none of them")
    void quotesEverySourceSetting() throws IllegalAccessException {
        // Derived from SourceFactory rather than listed, so a setting added there and not here
        // fails this test instead of being read back as "withheld" - which is what happened to
        // crawl_delay_ms: accepted, stored and honoured, and hidden from GET /v1/ingest.
        Map<String, String> config = new LinkedHashMap<>();
        for (String key : settingKeys()) {
            config.put(key, "1");
        }

        String json = SourceConfigView.toJson(config);

        assertThat(json).doesNotContain("withheld");
        for (String key : config.keySet()) {
            assertThat(json).contains("\"" + key + "\"");
        }
    }

    @Test
    @DisplayName("withholds a key it does not recognise, naming it without quoting its value")
    void withholdsUnknownKeys() {
        String json = SourceConfigView.toJson(Map.of("bearer_token", "s3cret"));

        assertThat(json).contains("withheld").contains("bearer_token").doesNotContain("s3cret");
    }

    private static List<String> settingKeys() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : SourceFactory.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && field.getType() == String.class && !field.getName().startsWith("TYPE_")) {
                keys.add((String) field.get(null));
            }
        }
        assertThat(keys).isNotEmpty();
        return keys;
    }
}
