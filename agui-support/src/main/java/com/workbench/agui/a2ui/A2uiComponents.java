package com.workbench.agui.a2ui;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.workbench.common.agui.ActionItem;
import com.workbench.common.agui.EvidenceItem;

import java.util.List;

/** Flat component entries for the {@code updateComponents.components} list (PLATFORM_CONTRACT.md §7.2). */
public final class A2uiComponents {

    private A2uiComponents() {
    }

    /** §7.2 — DecisionCard entry. */
    public static Object decisionCard(String id, String checklistId, String actionsId,
            String status, String disputeType, String evidenceReadiness, String recommendedAction) {
        return new DecisionCardEntry(id, "DecisionCard", status, disputeType, evidenceReadiness,
                recommendedAction, checklistId, actionsId);
    }

    /** §7.2 — EvidenceChecklist entry. */
    public static Object evidenceChecklist(String id, List<EvidenceItem> items) {
        return new EvidenceChecklistEntry(id, "EvidenceChecklist", items);
    }

    /** §7.2 — NextActions entry. */
    public static Object nextActions(String id, List<ActionItem> actions) {
        return new NextActionsEntry(id, "NextActions", actions);
    }

    /** §7.2 — ApprovalPreview entry. onApprove/onEdit/onCancel action ids are frozen by the contract. */
    public static Object approvalPreview(String id, String caseId, String newCaseStatus,
            List<String> missingItems, String actionAfterApproval) {
        return new ApprovalPreviewEntry(id, "ApprovalPreview", caseId, newCaseStatus, missingItems,
                actionAfterApproval,
                new ActionRef("approve_task_creation"),
                new ActionRef("edit_task_creation"),
                new ActionRef("cancel_task_creation"));
    }

    /** §7.2 — TaskCreatedCard entry. */
    public static Object taskCreatedCard(String id, String taskId, String caseStatus,
            String auditEntry, String nextOwner) {
        return new TaskCreatedCardEntry(id, "TaskCreatedCard", taskId, caseStatus, auditEntry, nextOwner);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DecisionCardEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("status") String status,
            @JsonProperty("disputeType") String disputeType,
            @JsonProperty("evidenceReadiness") String evidenceReadiness,
            @JsonProperty("recommendedAction") String recommendedAction,
            @JsonProperty("checklistId") String checklistId,
            @JsonProperty("actionsId") String actionsId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EvidenceChecklistEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("items") List<EvidenceItem> items) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record NextActionsEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("actions") List<ActionItem> actions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ApprovalPreviewEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("caseId") String caseId,
            @JsonProperty("newCaseStatus") String newCaseStatus,
            @JsonProperty("missingItems") List<String> missingItems,
            @JsonProperty("actionAfterApproval") String actionAfterApproval,
            @JsonProperty("onApprove") ActionRef onApprove,
            @JsonProperty("onEdit") ActionRef onEdit,
            @JsonProperty("onCancel") ActionRef onCancel) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TaskCreatedCardEntry(
            @JsonProperty("id") String id,
            @JsonProperty("component") String component,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("caseStatus") String caseStatus,
            @JsonProperty("auditEntry") String auditEntry,
            @JsonProperty("nextOwner") String nextOwner) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ActionRef(@JsonProperty("id") String id) {
    }
}
