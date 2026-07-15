package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CaseMcpClient {

    private final McpSyncClient client;

    public CaseMcpClient(List<McpSyncClient> syncClients) {
        if (syncClients.isEmpty()) {
            throw new IllegalStateException("No MCP sync client configured for case-system-mcp");
        }
        this.client = syncClients.get(0);
    }

    public Map<String, Object> getCase(String caseId) {
        return callTool("get_case", Map.of("caseId", caseId));
    }

    public Map<String, Object> listCaseDocuments(String caseId) {
        return callTool("list_case_documents", Map.of("caseId", caseId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool call failed: " + toolName);
        }
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("MCP tool " + toolName + " returned no structured content");
    }
}
