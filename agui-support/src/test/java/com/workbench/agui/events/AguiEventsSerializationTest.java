package com.workbench.agui.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AguiEventsSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void runStartedEventRoundTrips() {
        AguiEvents.RunStartedEvent original = new AguiEvents.RunStartedEvent("thread-1", "run-1");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_STARTED", node.get("type").asString());
        assertEquals("thread-1", node.get("threadId").asString());
        assertEquals("run-1", node.get("runId").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunStartedEvent.class));
    }

    @Test
    void runFinishedEventRoundTrips() {
        AguiEvents.RunFinishedEvent original = new AguiEvents.RunFinishedEvent("thread-1", "run-1");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_FINISHED", node.get("type").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunFinishedEvent.class));
    }

    @Test
    void runErrorEventWithCodeRoundTrips() {
        AguiEvents.RunErrorEvent original = new AguiEvents.RunErrorEvent("thread-1", "run-1", "boom", "E123");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("RUN_ERROR", node.get("type").asString());
        assertEquals("boom", node.get("message").asString());
        assertEquals("E123", node.get("code").asString());

        assertEquals(original, objectMapper.readValue(json, AguiEvents.RunErrorEvent.class));
    }

    @Test
    void runErrorEventWithoutCodeOmitsCodeField() {
        AguiEvents.RunErrorEvent original = new AguiEvents.RunErrorEvent("thread-1", "run-1", "boom");

        String json = objectMapper.writeValueAsString(original);

        assertFalse(json.contains("\"code\""));
    }

    @Test
    void customProgressEventRoundTrips() {
        AguiEvents.CustomEvent original = AguiEvents.CustomEvent.progress("orchestrator", "Understanding dispute...");

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("CUSTOM", node.get("type").asString());
        assertEquals("progress", node.get("name").asString());
        assertEquals("orchestrator", node.get("value").get("source").asString());
        assertEquals("Understanding dispute...", node.get("value").get("text").asString());
    }

    @Test
    void customA2uiEventWrapsArbitraryPayload() {
        AguiEvents.CustomEvent original = AguiEvents.CustomEvent.a2ui(Map.of("version", "v0.9"));

        String json = objectMapper.writeValueAsString(original);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("CUSTOM", node.get("type").asString());
        assertEquals("a2ui", node.get("name").asString());
        assertEquals("v0.9", node.get("value").get("version").asString());
    }

    @Test
    void stateDeltaEventWithReplaceOpSerializesToRfc6902Shape() {
        AguiEvents.StateDeltaEvent event = new AguiEvents.StateDeltaEvent(
                List.of(new JsonPatchOp("replace", "/evidenceReadiness", "2 of 4 required items present")));

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("STATE_DELTA", node.get("type").asString());
        JsonNode op = node.get("delta").get(0);
        assertEquals("replace", op.get("op").asString());
        assertEquals("/evidenceReadiness", op.get("path").asString());
        assertEquals("2 of 4 required items present", op.get("value").asString());
    }

    @Test
    void stateSnapshotEventWithPlainMapSnapshotSerializesCorrectly() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("evidenceReadiness", null);
        AguiEvents.StateSnapshotEvent event = new AguiEvents.StateSnapshotEvent(snapshot);

        String json = objectMapper.writeValueAsString(event);
        JsonNode node = objectMapper.readTree(json);
        assertEquals("STATE_SNAPSHOT", node.get("type").asString());
        assertTrue(node.get("snapshot").has("evidenceReadiness"));
    }
}
