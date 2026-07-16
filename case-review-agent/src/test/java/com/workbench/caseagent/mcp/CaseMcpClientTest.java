package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mocks McpSyncClient directly (Java API boundary) rather than WireMock (raw HTTP
 * boundary) — see case-review-agent/pom.xml and PLATFORM_CONTRACT.md Global
 * Constraint #6 in the plan for the reasoning. Fast, isolated, tests CaseMcpClient's
 * own mapping/error-handling contract, not the real MCP wire protocol (that was
 * already proven against real Postgres in Session 2's Testcontainers tests).
 */
class CaseMcpClientTest {

    @Test
    void getCase_mapsResponseCorrectly() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Map<String, Object> structured = Map.of("caseId", "D-10291", "amount", 250.00);
        when(mockClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(), false, structured, Map.of()));

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));
        Map<String, Object> result = client.getCase("D-10291");

        assertEquals("D-10291", result.get("caseId"));
        assertEquals(250.00, result.get("amount"));

        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mockClient).callTool(captor.capture());
        assertEquals("get_case", captor.getValue().name());
        assertEquals("D-10291", captor.getValue().arguments().get("caseId"));
    }

    @Test
    void listCaseDocuments_mapsDocTypeAndPresentFields() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Map<String, Object> structured = Map.of("documents", List.of(
                Map.of("docType", "TRANSACTION_RECORD", "present", true),
                Map.of("docType", "MERCHANT_RESPONSE", "present", true)));
        when(mockClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(), false, structured, Map.of()));

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));
        Map<String, Object> result = client.listCaseDocuments("D-10291");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertEquals(2, documents.size());
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
        assertEquals("TRANSACTION_RECORD", documents.get(0).get("docType"));
        assertEquals(true, documents.get(0).get("present"));
    }

    @Test
    void getCase_caseNotFound_throwsCaseNotFoundException() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        McpSchema.CallToolResult errorResult = new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("Case not found: D-99999").build()),
                true, null, Map.of());
        when(mockClient.callTool(any())).thenReturn(errorResult);

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));

        CaseNotFoundException exception =
                assertThrows(CaseNotFoundException.class, () -> client.getCase("D-99999"));
        assertTrue(exception.getMessage().contains("D-99999"));
    }

    @Test
    void getCase_genericMcpError_throwsIllegalStateException() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        McpSchema.CallToolResult errorResult = new McpSchema.CallToolResult(
                List.of(McpSchema.TextContent.builder("Database connection refused").build()),
                true, null, Map.of());
        when(mockClient.callTool(any())).thenReturn(errorResult);

        CaseMcpClient client = new CaseMcpClient(List.of(mockClient));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> client.getCase("D-10291"));
        assertTrue(exception.getMessage().contains("Database connection refused"));
    }
}
