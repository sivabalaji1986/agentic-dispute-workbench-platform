package com.workbench.mcp.tools;

import com.workbench.mcp.entity.AuditEntryEntity;
import com.workbench.mcp.entity.CaseEntity;
import com.workbench.mcp.entity.EvidenceDocumentEntity;
import com.workbench.mcp.entity.TaskEntity;
import com.workbench.mcp.repository.AuditEntryRepository;
import com.workbench.mcp.repository.CaseRepository;
import com.workbench.mcp.repository.EvidenceDocumentRepository;
import com.workbench.mcp.repository.TaskRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaseSystemTools {

    private final CaseRepository caseRepository;
    private final EvidenceDocumentRepository evidenceDocumentRepository;
    private final TaskRepository taskRepository;
    private final AuditEntryRepository auditEntryRepository;

    public CaseSystemTools(CaseRepository caseRepository,
            EvidenceDocumentRepository evidenceDocumentRepository,
            TaskRepository taskRepository,
            AuditEntryRepository auditEntryRepository) {
        this.caseRepository = caseRepository;
        this.evidenceDocumentRepository = evidenceDocumentRepository;
        this.taskRepository = taskRepository;
        this.auditEntryRepository = auditEntryRepository;
    }

    @Tool(name = "get_case", description = "Retrieve full case details by case ID")
    public Map<String, Object> getCase(
            @ToolParam(description = "The case identifier") String caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseEntity.getCaseId());
        result.put("disputeText", caseEntity.getDisputeText());
        result.put("disputeType", caseEntity.getDisputeType());
        result.put("caseStatus", caseEntity.getCaseStatus());
        result.put("amount", caseEntity.getAmount());
        result.put("currency", caseEntity.getCurrency());
        result.put("createdAt", caseEntity.getCreatedAt());
        result.put("updatedAt", caseEntity.getUpdatedAt());
        return result;
    }

    @Tool(name = "list_case_documents", description = "List all evidence documents for a case")
    public Map<String, Object> listCaseDocuments(
            @ToolParam(description = "The case identifier") String caseId) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (EvidenceDocumentEntity doc : evidenceDocumentRepository.findByCaseId(caseId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("docType", doc.getDocType());
            entry.put("present", doc.getPresent());
            documents.add(entry);
        }
        return Map.of("documents", documents);
    }

    @Tool(name = "create_task", description = "Create a missing-evidence request task for a case")
    @Transactional
    public Map<String, Object> createTask(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The task type, e.g. MISSING_EVIDENCE_REQUEST") String taskType,
            @ToolParam(description = "The document types missing from the case file") List<String> missingItems,
            @ToolParam(description = "The queue this task should be assigned to") String assignedQueue) {
        TaskEntity existing = taskRepository.findByCaseIdAndTaskType(caseId, taskType).orElse(null);
        if (existing != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", existing.getTaskId());
            result.put("createdAt", existing.getCreatedAt());
            return result;
        }

        TaskEntity task = new TaskEntity();
        task.setTaskId(generateTaskId());
        task.setCaseId(caseId);
        task.setTaskType(taskType);
        task.setMissingItems(missingItems.toArray(new String[0]));
        task.setAssignedQueue(assignedQueue);
        task.setCreatedAt(OffsetDateTime.now());
        taskRepository.save(task);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("createdAt", task.getCreatedAt());
        return result;
    }

    @Tool(name = "update_case_status", description = "Update the status of a case")
    @Transactional
    public Map<String, Object> updateCaseStatus(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The new case status") String newStatus) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        caseEntity.setCaseStatus(newStatus);
        caseEntity.setUpdatedAt(OffsetDateTime.now());
        caseRepository.save(caseEntity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseEntity.getCaseId());
        result.put("status", caseEntity.getCaseStatus());
        result.put("updatedAt", caseEntity.getUpdatedAt());
        return result;
    }

    @Tool(name = "create_audit_entry", description = "Create an audit log entry for a case action")
    @Transactional
    public Map<String, Object> createAuditEntry(
            @ToolParam(description = "The case identifier") String caseId,
            @ToolParam(description = "The action performed") String action,
            @ToolParam(description = "Who performed the action") String performedBy) {
        AuditEntryEntity entry = new AuditEntryEntity();
        entry.setCaseId(caseId);
        entry.setAction(action);
        entry.setPerformedBy(performedBy);
        entry.setPerformedAt(OffsetDateTime.now());
        auditEntryRepository.save(entry);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryId", entry.getEntryId());
        result.put("createdAt", entry.getPerformedAt());
        return result;
    }

    private static String generateTaskId() {
        return "EVID-" + String.format("%05d", ThreadLocalRandom.current().nextInt(100_000));
    }
}
