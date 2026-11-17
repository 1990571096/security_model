package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdJsonSupportTest {

    private static final Long OVERSIZED = 9007199254740993L;

    @Test
    void mapKeepsIdentifierExactAndOrdinaryLongNumeric() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("taskId", OVERSIZED);
        source.put("sessionId", 42L);
        source.put("durationMs", 123L);
        source.put("progress", 30);
        source.put("nested", Map.of("exportId", 700L, "count", 2));

        Map<?, ?> normalized = assertInstanceOf(Map.class, IdJsonSupport.normalize(source));
        assertEquals("9007199254740993", normalized.get("taskId"));
        assertEquals("42", normalized.get("sessionId"));
        assertEquals(123L, normalized.get("durationMs"));
        assertEquals(30, normalized.get("progress"));
        assertEquals("700", assertInstanceOf(Map.class, normalized.get("nested")).get("exportId"));
    }

    @Test
    void jsonTreeAndListKeepIdentifierExact() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode source = mapper.readTree("""
                {"taskId":9007199254740993,"runningDuration":12,
                 "items":[{"datasetId":7,"count":1}]}
                """);
        JsonNode normalized = assertInstanceOf(JsonNode.class, IdJsonSupport.normalize(source));
        assertTrue(normalized.path("taskId").isTextual());
        assertEquals("9007199254740993", normalized.path("taskId").asText());
        assertTrue(normalized.path("runningDuration").isNumber());
        assertTrue(normalized.path("items").get(0).path("datasetId").isTextual());

        List<?> list = List.of(Map.of("confirmationId", 12L, "progress", 4));
        Map<?, ?> item = assertInstanceOf(Map.class,
                assertInstanceOf(List.class, IdJsonSupport.normalize(list)).get(0));
        assertEquals("12", item.get("confirmationId"));
        assertEquals(4, item.get("progress"));
    }

    @Test
    void beanModifierCoversTaskAndExportIdentifiers() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new IdJacksonConfig().idStringSerializerCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();

        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(OVERSIZED);
        task.setDatasetId(7L);
        task.setProgress(30);
        JsonNode taskJson = mapper.readTree(mapper.writeValueAsString(task));
        assertEquals("9007199254740993", taskJson.path("taskId").asText());
        assertTrue(taskJson.path("taskId").isTextual());
        assertEquals("7", taskJson.path("datasetId").asText());
        assertTrue(taskJson.path("progress").isNumber());

        CleanSubsetExportTask export = new CleanSubsetExportTask();
        export.setExportId(8L);
        export.setTaskId(OVERSIZED);
        JsonNode exportJson = mapper.readTree(mapper.writeValueAsString(export));
        assertEquals("8", exportJson.path("exportId").asText());
        assertEquals("9007199254740993", exportJson.path("taskId").asText());
    }
}
