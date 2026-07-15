package com.workbench.common.merge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceReadiness(int present, int required, List<String> missingItems, String label) {

    public static EvidenceReadiness compute(List<String> available, List<String> required) {
        List<String> missingItems = new ArrayList<>();
        int presentCount = 0;
        for (String requiredItem : required) {
            boolean found = available.stream().anyMatch(item -> item.equalsIgnoreCase(requiredItem));
            if (found) {
                presentCount++;
            } else {
                missingItems.add(DocumentTypes.humanReadable(requiredItem));
            }
        }
        String label = presentCount + " of " + required.size() + " required items present";
        return new EvidenceReadiness(presentCount, required.size(), missingItems, label);
    }
}
