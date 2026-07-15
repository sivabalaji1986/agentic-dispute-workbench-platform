package com.workbench.contract;

import com.workbench.agui.a2ui.A2uiComponents;
import com.workbench.agui.a2ui.A2uiMessages;
import com.workbench.common.agui.ActionItem;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A2uiMessages/A2uiComponents deliberately return {@code Object} backed by private nested
 * records (PLATFORM_CONTRACT.md §11) — there is no named, accessible Java type to deserialize
 * a fixture into. Each test instead invokes the real builder, serializes its live output, and
 * compares both the exact JSON string and the parsed JsonNode tree against the frozen fixture,
 * so a field rename or shape change in the builder fails here immediately.
 */
class A2uiPayloadContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void createSurfaceMatchesFixture() throws IOException {
        String fixture = loadFixture("a2ui-create-surface.json");

        Object live = A2uiMessages.createSurface(
                "case-D-10291", "https://dispute-workbench.internal/catalogs/v1.json");
        String liveJson = objectMapper.writeValueAsString(live);

        assertEquals(fixture, liveJson);
        assertEquals(objectMapper.readTree(fixture), objectMapper.readTree(liveJson));
    }

    @Test
    void updateComponentsFullMatchesFixture() throws IOException {
        String fixture = loadFixture("a2ui-update-components-full.json");

        Object decisionCard = A2uiComponents.decisionCard(
                "decision-1", "checklist-1", "actions-1",
                "Needs More Evidence", "Goods Not Received",
                "2 of 4 required items present", "Create evidence request task");
        Object checklist = A2uiComponents.evidenceChecklist("checklist-1", List.of(
                new EvidenceItem("Transaction record", true),
                new EvidenceItem("Merchant response", true),
                new EvidenceItem("Customer declaration", false),
                new EvidenceItem("Delivery / non-delivery proof", false)));
        Object actions = A2uiComponents.nextActions("actions-1", List.of(
                new ActionItem("create_evidence_request_task", "Create Evidence Request Task"),
                new ActionItem("escalate_to_reviewer", "Escalate to Reviewer"),
                new ActionItem("save_case_note", "Save Case Note")));
        Object live = A2uiMessages.updateComponents("case-D-10291", List.of(decisionCard, checklist, actions));
        String liveJson = objectMapper.writeValueAsString(live);

        assertEquals(fixture, liveJson);
        assertEquals(objectMapper.readTree(fixture), objectMapper.readTree(liveJson));

        JsonNode decisionCardNode = objectMapper.readTree(liveJson)
                .get("updateComponents").get("components").get(0);
        assertEquals("checklist-1", decisionCardNode.get("checklistId").asString());
        assertEquals("actions-1", decisionCardNode.get("actionsId").asString());
    }

    @Test
    void approvalPreviewMatchesFixture() throws IOException {
        String fixture = loadFixture("a2ui-approval-preview.json");

        Object live = A2uiComponents.approvalPreview(
                "approval-1", "D-10291", "Pending Evidence",
                List.of("Customer declaration", "Delivery / non-delivery proof"),
                "Create task in case system and update case status.");
        String liveJson = objectMapper.writeValueAsString(live);

        assertEquals(fixture, liveJson);
        assertEquals(objectMapper.readTree(fixture), objectMapper.readTree(liveJson));
    }

    @Test
    void taskCreatedCardMatchesFixture() throws IOException {
        String fixture = loadFixture("a2ui-task-created-card.json");

        Object live = A2uiComponents.taskCreatedCard(
                "task-created-1", "EVID-88421", "Pending Evidence", "Created", "Dispute Operations Queue");
        String liveJson = objectMapper.writeValueAsString(live);

        assertEquals(fixture, liveJson);
        assertEquals(objectMapper.readTree(fixture), objectMapper.readTree(liveJson));
    }

    private static String loadFixture(String name) throws IOException {
        try (InputStream in = A2uiPayloadContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
