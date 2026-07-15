package com.workbench.mcp.test;

import com.workbench.mcp.repository.TaskRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
class CaseSystemMcpToolsTest {

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

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void getCase_existingCase_returnsAllFields() {
        Map<String, Object> result = caseSystemTools.getCase("D-10291");

        assertEquals("D-10291", result.get("caseId"));
        assertNotNull(result.get("disputeType"));
        assertNotNull(result.get("amount"));
        assertNotNull(result.get("currency"));
    }

    @Test
    void getCase_unknownCase_throwsDescriptiveException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> caseSystemTools.getCase("D-UNKNOWN"));
        assertTrue(ex.getMessage().contains("D-UNKNOWN"));
    }

    @Test
    void listCaseDocuments_returnsDocumentsWithCamelCaseFields() {
        Map<String, Object> result = caseSystemTools.listCaseDocuments("D-10291");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) result.get("documents");
        assertEquals(2, documents.size());
        assertTrue(documents.get(0).containsKey("docType"));
        assertTrue(documents.get(0).containsKey("present"));
    }

    @Test
    void createTask_newTask_insertsAndReturns() {
        Map<String, Object> result = caseSystemTools.createTask(
                "D-10291", "MISSING_EVIDENCE_REQUEST",
                List.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"), "Dispute Operations");

        assertTrue(((String) result.get("taskId")).startsWith("EVID-"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void createTask_duplicate_returnsExistingWithoutInsert() {
        Map<String, Object> first = caseSystemTools.createTask(
                "D-10291", "DUPLICATE_CHECK_TASK",
                List.of("CUSTOMER_DECLARATION"), "Dispute Operations");
        Map<String, Object> second = caseSystemTools.createTask(
                "D-10291", "DUPLICATE_CHECK_TASK",
                List.of("CUSTOMER_DECLARATION"), "Dispute Operations");

        assertEquals(first.get("taskId"), second.get("taskId"));

        long rowCount = taskRepository.findAll().stream()
                .filter(t -> "D-10291".equals(t.getCaseId()) && "DUPLICATE_CHECK_TASK".equals(t.getTaskType()))
                .count();
        assertEquals(1, rowCount);
    }

    @Test
    void updateCaseStatus_updatesAndReturnsTimestamp() {
        Map<String, Object> result = caseSystemTools.updateCaseStatus("D-10291", "PENDING_EVIDENCE");

        assertEquals("PENDING_EVIDENCE", result.get("status"));
        assertNotNull(result.get("updatedAt"));

        Map<String, Object> reloaded = caseSystemTools.getCase("D-10291");
        assertEquals("PENDING_EVIDENCE", reloaded.get("caseStatus"));
    }

    @Test
    void createAuditEntry_insertsAndReturnsEntryId() {
        Map<String, Object> result = caseSystemTools.createAuditEntry(
                "D-10291", "EVIDENCE_REQUEST_TASK_CREATED", "ORCHESTRATOR");

        assertInstanceOf(Integer.class, result.get("entryId"));
        assertTrue((Integer) result.get("entryId") > 0);
        assertNotNull(result.get("createdAt"));
    }
}
