package com.workbench.contract;

import com.workbench.agui.events.AguiEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AguiEventContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void runStartedEventMatchesFixture() throws IOException {
        String fixture = loadFixture("agui-run-started.json");

        AguiEvents.RunStartedEvent deserialized = objectMapper.readValue(fixture, AguiEvents.RunStartedEvent.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    @Test
    void customProgressEventMatchesFixture() throws IOException {
        String fixture = loadFixture("agui-custom-progress.json");

        AguiEvents.CustomEvent deserialized = objectMapper.readValue(fixture, AguiEvents.CustomEvent.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    @Test
    void customA2uiUpdateComponentsEventMatchesFixture() throws IOException {
        String fixture = loadFixture("agui-custom-a2ui-update-components.json");

        AguiEvents.CustomEvent deserialized = objectMapper.readValue(fixture, AguiEvents.CustomEvent.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    @Test
    void stateDeltaEventMatchesFixture() throws IOException {
        String fixture = loadFixture("agui-state-delta.json");

        AguiEvents.StateDeltaEvent deserialized = objectMapper.readValue(fixture, AguiEvents.StateDeltaEvent.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    private static String loadFixture(String name) throws IOException {
        try (InputStream in = AguiEventContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
