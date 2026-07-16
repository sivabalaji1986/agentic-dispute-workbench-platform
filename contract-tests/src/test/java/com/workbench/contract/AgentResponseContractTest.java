package com.workbench.contract;

import com.workbench.common.a2a.AgentResponse;
import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serializes an AgentResponse<CaseReviewResult> matching the demo scenario and
 * asserts the wire shape the orchestrator (Session 5) deserializes against:
 * result, progressLines, and retryable are siblings at the top level (retryable
 * is not nested under result). This is the generic wrapper policy-agent
 * (Session 4) and any future A2A server module reuses — see PLATFORM_CONTRACT.md
 * §10.
 */
class AgentResponseContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void agentResponseSerializesWithNestedResultAndTopLevelRetryable() {
        CaseReviewResult result = new CaseReviewResult(
                "D-10291", true, "SGD 250", "available", "Item was delivered",
                List.of(new EvidenceItem("Transaction record", true), new EvidenceItem("Merchant response", true)),
                "OPEN");
        AgentResponse<CaseReviewResult> response = new AgentResponse<>(
                result,
                List.of("Checking transaction status...", "Transaction found for SGD 250"),
                false);

        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.get("result").isObject());
        assertEquals("D-10291", node.get("result").get("caseId").asString());
        assertTrue(node.get("progressLines").isArray());
        assertEquals(2, node.get("progressLines").size());
        assertFalse(node.get("retryable").asBoolean());
        assertTrue(json.contains("\"retryable\":false"));

        AgentResponse<CaseReviewResult> roundTripped =
                objectMapper.readValue(json, new TypeReference<AgentResponse<CaseReviewResult>>() { });
        assertEquals(response, roundTripped);
    }
}
