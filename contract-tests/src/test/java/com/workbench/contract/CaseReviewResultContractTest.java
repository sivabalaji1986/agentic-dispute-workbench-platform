package com.workbench.contract;

import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constructs a CaseReviewResult matching the demo scenario (PLATFORM_CONTRACT.md §8.2)
 * and asserts the serialized shape the orchestrator (Session 5) will deserialize
 * against — in particular, that EvidenceItem labels are human-readable, not raw
 * docType codes (closes Session 1 hardening's Minor 2, now enforced by
 * case-review-agent's DocumentTypes usage).
 */
class CaseReviewResultContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void demoScenarioSerializesWithHumanReadableLabelsAndCamelCaseFields() {
        CaseReviewResult result = new CaseReviewResult(
                "D-10291",
                true,
                "SGD 250",
                "available",
                "Item was delivered",
                List.of(
                        new EvidenceItem("Transaction record", true),
                        new EvidenceItem("Merchant response", true)),
                "OPEN");

        String json = objectMapper.writeValueAsString(result);
        JsonNode node = objectMapper.readTree(json);

        assertEquals("Transaction record", node.get("availableDocuments").get(0).get("label").asString());
        assertFalse("TRANSACTION_RECORD".equals(node.get("availableDocuments").get(0).get("label").asString()));
        assertEquals("Merchant response", node.get("availableDocuments").get(1).get("label").asString());
        assertFalse("MERCHANT_RESPONSE".equals(node.get("availableDocuments").get(1).get("label").asString()));
        assertEquals("SGD 250", node.get("transactionAmount").asString());

        assertTrue(json.contains("\"caseId\":\"D-10291\""));
        assertTrue(json.contains("\"transactionFound\":true"));
        assertTrue(json.contains("\"transactionAmount\":\"SGD 250\""));
        assertTrue(json.contains("\"merchantResponse\":\"available\""));
        assertTrue(json.contains("\"merchantPosition\":\"Item was delivered\""));
        assertTrue(json.contains("\"availableDocuments\":[{\"label\":\"Transaction record\",\"present\":true},"
                + "{\"label\":\"Merchant response\",\"present\":true}]"));
        assertTrue(json.contains("\"caseStatus\":\"OPEN\""));

        CaseReviewResult roundTripped = objectMapper.readValue(json, CaseReviewResult.class);
        assertEquals(result, roundTripped);
    }
}
