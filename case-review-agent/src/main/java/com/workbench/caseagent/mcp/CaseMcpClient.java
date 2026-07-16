package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CaseMcpClient {

    private static final String CASE_NOT_FOUND_PREFIX = "Case not found:";

    private final McpSyncClient client;

    public CaseMcpClient(List<McpSyncClient> syncClients) {
        if (syncClients.isEmpty()) {
            throw new IllegalStateException("No MCP sync client configured for case-system-mcp");
        }
        this.client = syncClients.get(0);
    }

    public Map<String, Object> getCase(String caseId) {
        McpSchema.CallToolResult result = call("get_case", Map.of("caseId", caseId));
        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = extractErrorText(result);
            if (errorText.startsWith(CASE_NOT_FOUND_PREFIX)) {
                throw new CaseNotFoundException(errorText);
            }
            throw new IllegalStateException("MCP tool call failed: get_case: " + errorText);
        }
        return extractStructuredContent(result, "get_case");
    }

    public Map<String, Object> listCaseDocuments(String caseId) {
        return callTool("list_case_documents", Map.of("caseId", caseId));
    }

    private Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = call(toolName, arguments);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool call failed: " + toolName + ": " + extractErrorText(result));
        }
        return extractStructuredContent(result, toolName);
    }

    private McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(arguments)
                .build();
        return client.callTool(request);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractStructuredContent(McpSchema.CallToolResult result, String toolName) {
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("MCP tool " + toolName + " returned no structured content");
    }

    private static String extractErrorText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("Unknown MCP error");
    }
}
