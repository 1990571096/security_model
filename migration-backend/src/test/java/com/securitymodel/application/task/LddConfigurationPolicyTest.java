package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LddConfigurationPolicyTest {

    private static final String VALID_CONFIG = """
            {"dataset":"cifar10","trigger_type":"SIG","trigger_label":0,
             "poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,
             "num_epochs":5,"lr_poison":0.001,"r":0.75,"alpha1":0.1,"alpha2":0.5,
             "seed":123}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LddConfigurationPolicy policy = new LddConfigurationPolicy(objectMapper);

    @Test
    void acceptsEveryDatasetAndTriggerDeclaredByTheSeedSchema() throws Exception {
        for (String dataset : LddConfigurationPolicy.SUPPORTED_DATASETS) {
            for (String trigger : LddConfigurationPolicy.SUPPORTED_TRIGGER_TYPES) {
                ObjectNode config = validConfig();
                config.put("dataset", dataset);
                config.put("trigger_type", trigger);
                policy.normalizeAndValidate(config.toString(), "{}", "{}");
            }
        }
    }

    @Test
    void rejectsUnknownDatasetWithStableValueCode() throws Exception {
        ObjectNode config = validConfig();
        config.put("dataset", "unknown");
        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> policy.normalizeAndValidate(config.toString(), "{}", "{}"));
        assertEquals("PARAMETER_VALUE_INVALID", exception.getCode());
    }

    @Test
    void rejectsUnknownTriggerWithStableValueCode() throws Exception {
        ObjectNode config = validConfig();
        config.put("trigger_type", "not-a-trigger");
        TaskCommandException exception = assertThrows(TaskCommandException.class,
                () -> policy.normalizeAndValidate(config.toString(), "{}", "{}"));
        assertEquals("PARAMETER_VALUE_INVALID", exception.getCode());
    }

    @Test
    void rejectsNumericBooleanAndObjectDatasetAsTypeErrors() throws Exception {
        for (String value : new String[]{"123", "true", "{\"nested\":\"cifar10\"}"}) {
            ObjectNode config = validConfig();
            config.set("dataset", objectMapper.readTree(value));
            TaskCommandException exception = assertThrows(TaskCommandException.class,
                    () -> policy.normalizeAndValidate(config.toString(), "{}", "{}"));
            assertEquals("PARAMETER_TYPE_INVALID", exception.getCode());
        }
    }

    @Test
    void rejectsNumericBooleanAndObjectTriggerAsTypeErrors() throws Exception {
        for (String value : new String[]{"123", "false", "[\"SIG\"]"}) {
            ObjectNode config = validConfig();
            config.set("trigger_type", objectMapper.readTree(value));
            TaskCommandException exception = assertThrows(TaskCommandException.class,
                    () -> policy.normalizeAndValidate(config.toString(), "{}", "{}"));
            assertEquals("PARAMETER_TYPE_INVALID", exception.getCode());
        }
    }

    @Test
    void rejectsNonTextDataPathAndPoisonedMode() throws Exception {
        ObjectNode config = validConfig();
        config.put("data_path", true);
        TaskCommandException dataPath = assertThrows(TaskCommandException.class,
                () -> policy.normalizeAndValidate(config.toString(), "{}", "{}"));
        assertEquals("PARAMETER_TYPE_INVALID", dataPath.getCode());

        ObjectNode modeConfig = validConfig();
        modeConfig.put("poisoned_mode", 1);
        TaskCommandException mode = assertThrows(TaskCommandException.class,
                () -> policy.normalizeAndValidate(modeConfig.toString(), "{}", "{}"));
        assertEquals("PARAMETER_TYPE_INVALID", mode.getCode());
    }

    @Test
    void supportedSetsMatchSeedSchemaOptions() throws Exception {
        JsonNode schema = seedConfigSchema();
        assertEquals(LddConfigurationPolicy.SUPPORTED_DATASETS,
                textSet(schema.path("dataset").path("options")));
        assertEquals(LddConfigurationPolicy.SUPPORTED_TRIGGER_TYPES,
                textSet(schema.path("trigger_type").path("options")));
    }

    private ObjectNode validConfig() throws Exception {
        return (ObjectNode) objectMapper.readTree(VALID_CONFIG);
    }

    private JsonNode seedConfigSchema() throws IOException {
        Path seed = Path.of("sql", "security_model_vab_seed.sql");
        if (!Files.exists(seed)) {
            seed = Path.of("..", "sql", "security_model_vab_seed.sql");
        }
        assertTrue(Files.exists(seed), "seed SQL must be available to verify schema/policy alignment");
        String line = Files.readAllLines(seed).stream()
                .filter(candidate -> candidate.contains("\"dataset\":{\"type\":\"select\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LDD config_schema line not found"));
        int start = line.indexOf("'{");
        int end = line.lastIndexOf("}'");
        assertTrue(start >= 0 && end > start, "LDD config_schema JSON literal not found");
        return objectMapper.readTree(line.substring(start + 1, end + 1));
    }

    private Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
