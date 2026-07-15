package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import com.workbench.common.merge.DocumentTypes;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CaseReviewAgentExecutor {

    private static final Pattern INPUT_PATTERN =
            Pattern.compile("dispute case ([\\w-]+), dispute type (\\w+)");

    private static final Set<String> CUSTOMER_DOC_TYPES =
            Set.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    private final CaseMcpClient caseMcpClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public CaseReviewAgentExecutor(CaseMcpClient caseMcpClient, ChatClient.Builder chatClientBuilder) {
        this.caseMcpClient = caseMcpClient;
        this.chatClient = chatClientBuilder.build();
    }

    public String execute(String messageText) {
        Matcher matcher = INPUT_PATTERN.matcher(messageText == null ? "" : messageText);
        if (!matcher.find()) {
            return errorResponse("UNKNOWN", "Unable to parse dispute case request");
        }
        String caseId = matcher.group(1);

        List<String> progressLines = new ArrayList<>();
        progressLines.add("Checking transaction status...");

        Map<String, Object> caseData;
        try {
            caseData = caseMcpClient.getCase(caseId);
        } catch (RuntimeException e) {
            return errorResponse(caseId, "Case not found: " + caseId);
        }

        String transactionAmount = formatAmount(caseData);
        progressLines.add("Transaction found for " + transactionAmount);
        progressLines.add("Merchant response available");

        Map<String, Object> documentsResponse = caseMcpClient.listCaseDocuments(caseId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents =
                (List<Map<String, Object>>) documentsResponse.getOrDefault("documents", List.of());

        List<EvidenceItem> availableDocuments = new ArrayList<>();
        boolean hasCustomerDocs = false;
        for (Map<String, Object> doc : documents) {
            String docType = (String) doc.get("docType");
            boolean present = Boolean.TRUE.equals(doc.get("present"));
            availableDocuments.add(new EvidenceItem(DocumentTypes.humanReadable(docType), present));
            if (present) {
                progressLines.add("Case file contains " + DocumentTypes.humanReadable(docType).toLowerCase());
            }
            if (CUSTOMER_DOC_TYPES.contains(docType) && present) {
                hasCustomerDocs = true;
            }
        }
        if (!hasCustomerDocs) {
            progressLines.add("No additional customer documents found in case file");
        }

        LlmSummary summary = summarizeMerchantResponse(caseData);

        CaseReviewResult result = new CaseReviewResult(
                caseId,
                true,
                transactionAmount,
                summary.merchantResponse(),
                summary.merchantPosition(),
                availableDocuments,
                (String) caseData.get("caseStatus"));

        return serialize(new AgentResponse(result, progressLines));
    }

    private LlmSummary summarizeMerchantResponse(Map<String, Object> caseData) {
        String caseJson = objectMapper.writeValueAsString(caseData);
        String prompt = "Given this case data: " + caseJson + "\n"
                + "Summarise: 1) Has the merchant responded? (yes/no) 2) What is the merchant's "
                + "position in one sentence?\n"
                + "Respond only with JSON: {\"merchantResponse\": \"...\", \"merchantPosition\": \"...\"}";
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            JsonNode node = objectMapper.readTree(content);
            return new LlmSummary(node.get("merchantResponse").asString(), node.get("merchantPosition").asString());
        } catch (RuntimeException e) {
            return new LlmSummary("unknown", "Unable to determine");
        }
    }

    private static String formatAmount(Map<String, Object> caseData) {
        Object amountObj = caseData.get("amount");
        Object currencyObj = caseData.get("currency");
        String currency = currencyObj == null ? "" : currencyObj.toString();
        if (amountObj instanceof BigDecimal bigDecimal) {
            return (currency + " " + bigDecimal.stripTrailingZeros().toPlainString()).trim();
        }
        return (currency + " " + amountObj).trim();
    }

    private String errorResponse(String caseId, String message) {
        CaseReviewResult errorResult = new CaseReviewResult(
                caseId, false, null, "unknown", message, List.of(), "UNKNOWN");
        return serialize(new AgentResponse(errorResult, List.of(message)));
    }

    private String serialize(AgentResponse response) {
        return objectMapper.writeValueAsString(response);
    }

    private record LlmSummary(String merchantResponse, String merchantPosition) {
    }
}
