package com.workbench.common.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingApproval(
        String threadId,
        String surfaceId,
        String caseId,
        String taskType,
        List<String> missingItems,
        boolean executed) {

    public PendingApproval withExecuted(boolean executed) {
        return new PendingApproval(threadId, surfaceId, caseId, taskType, missingItems, executed);
    }
}
