package com.workbench.contract;

import com.workbench.mcp.CaseSystemMcpApplication;
import com.workbench.mcp.tools.CaseSystemTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real case-system-mcp application context and calls its tool service
 * directly (not over MCP/HTTP) to assert the exact field names PLATFORM_CONTRACT.md
 * §9 promises. This catches field-name drift (a renamed key, a case change) before
 * Session 3's Case Review Agent tries to parse these responses.
 */
@SpringBootTest(classes = CaseSystemMcpApplication.class)
@Testcontainers
class McpToolOutputContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workbench")
            .withUsername("workbench")
            .withPassword("workbench")
            .withInitScripts("schema.sql", "seed-data.sql");

    @Autowired
    private CaseSystemTools caseSystemTools;

    @Test
    void listCaseDocumentsResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.listCaseDocuments("D-10291");

        assertTrue(result.containsKey("documents"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
    }

    @Test
    void createTaskResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.createTask(
                "D-10291", "CONTRACT_TEST_TASK", List.of("CUSTOMER_DECLARATION"), "Dispute Operations");

        assertTrue(result.containsKey("taskId"));
        assertTrue(result.containsKey("createdAt"));
    }

    @Test
    void updateCaseStatusResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.updateCaseStatus("D-10291", "PENDING_EVIDENCE");

        assertTrue(result.containsKey("caseId"));
        assertTrue(result.containsKey("status"));
        assertTrue(result.containsKey("updatedAt"));
    }

    @Test
    void createAuditEntryResponseHasContractFieldNames() {
        Map<String, Object> result = caseSystemTools.createAuditEntry(
                "D-10291", "EVIDENCE_REQUEST_TASK_CREATED", "ORCHESTRATOR");

        assertTrue(result.containsKey("entryId"));
        assertTrue(result.containsKey("createdAt"));
    }
}
