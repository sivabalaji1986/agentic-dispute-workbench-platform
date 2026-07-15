package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewRequest(String caseId, String disputeType) {
}
