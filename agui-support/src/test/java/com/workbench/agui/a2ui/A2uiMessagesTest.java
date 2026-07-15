package com.workbench.agui.a2ui;

import com.workbench.common.agui.ActionItem;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2uiMessagesTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void createSurfaceSerializesToExactShape() {
        Object message = A2uiMessages.createSurface(
                "case-D-10291", "https://dispute-workbench.internal/catalogs/v1.json");

        String json = objectMapper.writeValueAsString(message);

        assertEquals(
                "{\"version\":\"v0.9\",\"createSurface\":{\"surfaceId\":\"case-D-10291\","
                        + "\"catalogId\":\"https://dispute-workbench.internal/catalogs/v1.json\"}}",
                json);
    }

    @Test
    void updateComponentsWithDecisionCardChecklistAndActionsProducesSection72Shape() {
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

        Object message = A2uiMessages.updateComponents("case-D-10291", List.of(decisionCard, checklist, actions));

        String json = objectMapper.writeValueAsString(message);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("v0.9", node.get("version").asString());
        assertEquals("case-D-10291", node.get("updateComponents").get("surfaceId").asString());
        JsonNode components = node.get("updateComponents").get("components");
        assertEquals(3, components.size());

        JsonNode decisionCardNode = components.get(0);
        assertEquals("DecisionCard", decisionCardNode.get("component").asString());
        assertEquals("checklist-1", decisionCardNode.get("checklistId").asString());
        assertEquals("actions-1", decisionCardNode.get("actionsId").asString());

        JsonNode checklistNode = components.get(1);
        assertEquals("EvidenceChecklist", checklistNode.get("component").asString());
        assertEquals(4, checklistNode.get("items").size());
        assertTrue(checklistNode.get("items").get(0).get("present").asBoolean());

        JsonNode actionsNode = components.get(2);
        assertEquals("NextActions", actionsNode.get("component").asString());
        assertEquals(3, actionsNode.get("actions").size());
    }

    @Test
    void approvalPreviewSerializesWithAllFieldsPerSection72() {
        Object approvalPreview = A2uiComponents.approvalPreview(
                "approval-1", "D-10291", "Pending Evidence",
                List.of("Customer declaration", "Delivery / non-delivery proof"),
                "Create task in case system and update case status.");

        String json = objectMapper.writeValueAsString(approvalPreview);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("approval-1", node.get("id").asString());
        assertEquals("ApprovalPreview", node.get("component").asString());
        assertEquals("D-10291", node.get("caseId").asString());
        assertEquals("Pending Evidence", node.get("newCaseStatus").asString());
        assertEquals(2, node.get("missingItems").size());
        assertEquals("Create task in case system and update case status.",
                node.get("actionAfterApproval").asString());
        assertEquals("approve_task_creation", node.get("onApprove").get("id").asString());
        assertEquals("edit_task_creation", node.get("onEdit").get("id").asString());
        assertEquals("cancel_task_creation", node.get("onCancel").get("id").asString());
    }

    @Test
    void taskCreatedCardSerializesWithAllFieldsPerSection72() {
        Object taskCreatedCard = A2uiComponents.taskCreatedCard(
                "task-created-1", "EVID-88421", "Pending Evidence", "Created", "Dispute Operations Queue");

        String json = objectMapper.writeValueAsString(taskCreatedCard);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("task-created-1", node.get("id").asString());
        assertEquals("TaskCreatedCard", node.get("component").asString());
        assertEquals("EVID-88421", node.get("taskId").asString());
        assertEquals("Pending Evidence", node.get("caseStatus").asString());
        assertEquals("Created", node.get("auditEntry").asString());
        assertEquals("Dispute Operations Queue", node.get("nextOwner").asString());
    }

    @Test
    void evidenceChecklistDefensivelyCopiesItemsList() {
        List<EvidenceItem> mutableItems = new ArrayList<>();
        mutableItems.add(new EvidenceItem("Transaction record", true));

        Object checklist = A2uiComponents.evidenceChecklist("checklist-1", mutableItems);
        mutableItems.add(new EvidenceItem("Merchant response", true));

        String json = objectMapper.writeValueAsString(checklist);
        JsonNode node = objectMapper.readTree(json);

        assertEquals(1, node.get("items").size());
    }
}
