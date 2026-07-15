package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewResult(
        String caseId,
        boolean transactionFound,
        String transactionAmount,
        String merchantResponse,
        String merchantPosition,
        List<String> availableDocuments,
        String caseStatus) {
}
