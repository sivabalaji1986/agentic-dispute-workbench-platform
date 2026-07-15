package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseReviewAgentExecutorTest {

    private static final String DEMO_MESSAGE =
            "Check transaction, merchant response, case status and available evidence "
                    + "for dispute case D-10291, dispute type GOODS_NOT_RECEIVED.";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private CaseMcpClient mockMcpClient() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-10291")).thenReturn(Map.of(
                "caseId", "D-10291",
                "disputeText", "I paid SGD 250 for an item, but I never received it. "
                        + "The merchant says the item was delivered, but I disagree.",
                "disputeType", "GOODS_NOT_RECEIVED",
                "caseStatus", "OPEN",
                "amount", 250.00,
                "currency", "SGD"));
        when(client.listCaseDocuments("D-10291")).thenReturn(Map.of("documents", List.of(
                Map.of("docType", "TRANSACTION_RECORD", "present", true),
                Map.of("docType", "MERCHANT_RESPONSE", "present", true))));
        return client;
    }

    private ChatClient.Builder chatClientBuilderReturning(String jsonContent) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(jsonContent);
        return builder;
    }

    @Test
    void execute_validCase_returnsStructuredResult() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");

        assertTrue(result.get("transactionFound").asBoolean());
        assertEquals("SGD 250", result.get("transactionAmount").asString());
        assertEquals(2, result.get("availableDocuments").size());
    }

    @Test
    void execute_validCase_progressLinesPresent() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode progressLines = objectMapper.readTree(responseJson).get("progressLines");

        List<String> lines = new java.util.ArrayList<>();
        progressLines.forEach(n -> lines.add(n.asString()));

        assertTrue(lines.contains("Checking transaction status..."));
        assertTrue(lines.contains("Transaction found for SGD 250"));
        assertTrue(lines.contains("Merchant response available"));
        assertTrue(lines.contains("Case file contains transaction record"));
        assertTrue(lines.contains("Case file contains merchant response"));
        assertTrue(lines.contains("No additional customer documents found in case file"));
    }

    @Test
    void execute_documentsUseHumanReadableLabels() {
        String llmJson = "{\"merchantResponse\": \"available\", \"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode documents = objectMapper.readTree(responseJson).get("result").get("availableDocuments");

        List<String> labels = new java.util.ArrayList<>();
        documents.forEach(n -> labels.add(n.get("label").asString()));

        assertFalse(labels.contains("TRANSACTION_RECORD"));
        assertFalse(labels.contains("MERCHANT_RESPONSE"));
        assertTrue(labels.contains("Transaction record"));
        assertTrue(labels.contains("Merchant response"));
    }

    @Test
    void execute_unknownCase_returnsErrorGracefully() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-UNKNOWN")).thenThrow(new IllegalStateException("Case not found: D-UNKNOWN"));
        String message = "Check transaction, merchant response, case status and available evidence "
                + "for dispute case D-UNKNOWN, dispute type GOODS_NOT_RECEIVED.";

        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(client, chatClientBuilderReturning("{}"));

        String responseJson = executor.execute(message);
        JsonNode result = objectMapper.readTree(responseJson).get("result");

        assertFalse(result.get("transactionFound").asBoolean());
        assertTrue(result.get("merchantPosition").asString().contains("D-UNKNOWN"));
    }

    @Test
    void execute_malformedLlmOutput_defaultsGracefully() {
        CaseReviewAgentExecutor executor =
                new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning("not valid json"));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode result = objectMapper.readTree(responseJson).get("result");

        assertEquals("unknown", result.get("merchantResponse").asString());
        assertEquals("Unable to determine", result.get("merchantPosition").asString());
    }
}
