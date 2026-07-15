package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbench.common.agui.EvidenceItem;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewResult(
        String caseId,
        boolean transactionFound,
        String transactionAmount,
        String merchantResponse,
        String merchantPosition,
        List<EvidenceItem> availableDocuments,
        String caseStatus) {

    public CaseReviewResult {
        availableDocuments = availableDocuments == null ? List.of() : List.copyOf(availableDocuments);
    }
}
