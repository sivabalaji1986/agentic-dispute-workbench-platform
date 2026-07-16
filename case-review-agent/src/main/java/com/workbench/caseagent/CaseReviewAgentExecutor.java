package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import com.workbench.caseagent.mcp.CaseNotFoundException;
import com.workbench.common.a2a.AgentResponse;
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

    private static final String MERCHANT_RESPONSE_DOC_TYPE = "MERCHANT_RESPONSE";

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
            return errorResult("UNKNOWN", "Unable to parse dispute case request", false);
        }
        String caseId = matcher.group(1);

        List<String> progressLines = new ArrayList<>();
        progressLines.add("Checking transaction status...");

        Map<String, Object> caseData;
        try {
            caseData = caseMcpClient.getCase(caseId);
        } catch (CaseNotFoundException e) {
            return errorResult(caseId, "Case not found: " + caseId, false);
        } catch (RuntimeException e) {
            return errorResult(caseId, "Unable to retrieve case data: " + e.getMessage(), true);
        }

        String transactionAmount = formatAmount(caseData);
        progressLines.add("Transaction found for " + transactionAmount);

        Map<String, Object> documentsResponse;
        try {
            documentsResponse = caseMcpClient.listCaseDocuments(caseId);
        } catch (RuntimeException e) {
            return errorResult(caseId, "Unable to retrieve case documents: " + e.getMessage(), true);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents =
                (List<Map<String, Object>>) documentsResponse.getOrDefault("documents", List.of());

        boolean merchantResponded = documents.stream().anyMatch(doc ->
                MERCHANT_RESPONSE_DOC_TYPE.equals(doc.get("docType")) && Boolean.TRUE.equals(doc.get("present")));
        progressLines.add(merchantResponded ? "Merchant response available" : "No merchant response on file");

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

        String merchantResponseStatus = merchantResponded ? "available" : "not available";
        String merchantPosition = merchantResponded
                ? summarizeMerchantPosition(caseData)
                : "No merchant response on file";

        CaseReviewResult result = new CaseReviewResult(
                caseId,
                true,
                transactionAmount,
                merchantResponseStatus,
                merchantPosition,
                availableDocuments,
                (String) caseData.get("caseStatus"));

        return serialize(new AgentResponse<>(result, progressLines, false));
    }

    private String summarizeMerchantPosition(Map<String, Object> caseData) {
        String caseJson = objectMapper.writeValueAsString(caseData);
        String prompt = "Given this case data: " + caseJson + "\n"
                + "Summarise the merchant's position in one sentence.\n"
                + "Respond only with JSON: {\"merchantPosition\": \"...\"}";
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            JsonNode node = objectMapper.readTree(content);
            return node.get("merchantPosition").asString();
        } catch (RuntimeException e) {
            return "Unable to determine";
        }
    }

    private static String formatAmount(Map<String, Object> caseData) {
        Object amountObj = caseData.get("amount");
        Object currencyObj = caseData.get("currency");
        String currency = currencyObj == null ? "" : currencyObj.toString();
        if (amountObj instanceof Number number) {
            BigDecimal amount = new BigDecimal(number.toString());
            return (currency + " " + amount.stripTrailingZeros().toPlainString()).trim();
        }
        return (currency + " " + amountObj).trim();
    }

    private String errorResult(String caseId, String message, boolean retryable) {
        CaseReviewResult result = new CaseReviewResult(
                caseId, false, null, "unknown", null, List.of(), "UNKNOWN", message);
        return serialize(new AgentResponse<>(result, List.of(message), retryable));
    }

    private String serialize(AgentResponse<CaseReviewResult> response) {
        return objectMapper.writeValueAsString(response);
    }
}
